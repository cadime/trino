/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.deltalake.metastore.unitycatalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.StringResponseHandler;
import io.airlift.json.JsonCodec;
import io.trino.spi.TrinoException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static io.airlift.http.client.Request.Builder.preparePost;
import static io.airlift.http.client.StaticBodyGenerator.createStaticBodyGenerator;
import static io.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.trino.plugin.deltalake.DeltaLakeErrorCode.DELTA_LAKE_BAD_DATA;
import static java.util.Objects.requireNonNull;

public class OAuth2ClientCredentialsAuthProvider
        implements UnityCatalogAuthProvider
{
    private static final JsonCodec<TokenResponse> TOKEN_CODEC = jsonCodec(TokenResponse.class);

    private final URI tokenUri;
    private final String basicAuthHeader;
    private final String requestBody;
    private final long refreshSkewSeconds;
    private final HttpClient httpClient;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile String cachedHeader;
    private volatile Instant expiresAt = Instant.MIN;

    @Inject
    public OAuth2ClientCredentialsAuthProvider(OAuth2AuthConfig config, @ForUnityCatalog HttpClient httpClient)
    {
        requireNonNull(config, "config is null");
        this.tokenUri = config.getTokenUri();
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.basicAuthHeader = "Basic " + Base64.getEncoder().encodeToString(
                (config.getClientId() + ":" + config.getClientSecret()).getBytes(StandardCharsets.UTF_8));
        this.requestBody = "grant_type=client_credentials"
                + "&scope=" + URLEncoder.encode(config.getScope(), StandardCharsets.UTF_8);
        this.refreshSkewSeconds = config.getRefreshSkew().roundTo(TimeUnit.SECONDS);
    }

    @Override
    public String authorizationHeaderValue()
    {
        if (Instant.now().isBefore(expiresAt)) {
            return cachedHeader;
        }
        lock.lock();
        try {
            if (Instant.now().isBefore(expiresAt)) {
                return cachedHeader;
            }
            refreshToken();
            return cachedHeader;
        }
        finally {
            lock.unlock();
        }
    }

    private void refreshToken()
    {
        Request request = preparePost()
                .setUri(tokenUri)
                .setHeader(AUTHORIZATION, basicAuthHeader)
                .setHeader(CONTENT_TYPE, "application/x-www-form-urlencoded")
                .setBodyGenerator(createStaticBodyGenerator(requestBody, StandardCharsets.UTF_8))
                .build();
        StringResponseHandler.StringResponse response = httpClient.execute(request, createStringResponseHandler());
        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            throw new TrinoException(DELTA_LAKE_BAD_DATA,
                    "Failed to fetch OAuth2 token from " + tokenUri + ". Status: " + response.getStatusCode() + ", Body: " + response.getBody());
        }
        TokenResponse parsed = TOKEN_CODEC.fromJson(response.getBody());
        if (parsed.accessToken() == null || parsed.accessToken().isEmpty()) {
            throw new TrinoException(DELTA_LAKE_BAD_DATA, "OAuth2 token endpoint returned empty access_token");
        }
        long lifetime = Math.max(parsed.expiresInSeconds() - refreshSkewSeconds, 1);
        this.cachedHeader = "Bearer " + parsed.accessToken();
        this.expiresAt = Instant.now().plusSeconds(lifetime);
    }

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresInSeconds)
    {
        @JsonCreator
        public TokenResponse {}
    }
}
