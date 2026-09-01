# Slim Trino image for the Unity Catalog fork

A Trino image carrying only what this fork needs: the Delta Lake connector
(with the Unity Catalog metastore), PostgreSQL, and the fork's Spark
compatibility functions — instead of the ~48 connectors in the upstream image.

The package is `core/trino-server-lakehouse`, a provisio module that layers
those plugins on top of `trino-server-core`.

## What is included

From `trino-server-core` (the engine and its standard extension points):

| Plugin | Why it matters |
| --- | --- |
| `password-authenticators` | Web UI / HTTP password authentication (file, LDAP, Salesforce) |
| `resource-group-managers` | Resource group configuration |
| `session-property-managers` | Session property overrides |
| `exchange-filesystem` | Fault-tolerant execution exchange spooling |
| `spooling-filesystem` | Client protocol spooling |
| `functions-python`, `geospatial` | Built-in function plugins shipped with the core package |

Added by this module:

| Plugin | Notes |
| --- | --- |
| `delta-lake` | Includes `trino-hdfs`, and already bundles the S3, Azure, GCS and Alluxio-cache filesystem support |
| `postgresql` | |
| `spark-functions` | This fork's Spark/Databricks function aliases |

The Web UI itself is part of the engine (`trino-server-main`), so it is always
present. No catalog properties are baked into the image — mount your own.

## Build

```bash
./mvnw install -pl core/trino-server-lakehouse,client/trino-cli -am -DskipTests
```

```bash
./core/docker/build.sh -p trino-server-lakehouse -a arm64 -t trino-uc
```

Use `-a amd64` (or `-a amd64,arm64`) for other architectures, and `-x` to skip
the container smoke test. The tag is `trino-uc:<version>-<arch>`.

## Run

```bash
cp docker/lakehouse/.env.example docker/lakehouse/.env
```

Fill in `.env`, then:

```bash
docker compose -f docker/lakehouse/docker-compose.yml up
```

The Web UI is at http://localhost:8080/ui/ — with no authenticator configured
it accepts any username and requires no password. To turn on real
authentication, set `http-server.authentication.type=PASSWORD` plus a
`password-authenticator.properties`, and serve over HTTPS.

Example catalog files live in `catalog/`. They read their secrets from the
environment via Trino's `${ENV:VAR}` substitution, so no credentials are
written to disk.
