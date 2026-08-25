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

/**
 * Translates a Unity Catalog view definition (Spark SQL) into Trino-compatible SQL.
 *
 * <p>Implementations may return the input unchanged (pass-through) or perform a
 * full dialect translation via Coral. They MUST NOT throw on translation failure;
 * pass-through is always an acceptable fallback so the user gets to see the view.
 */
public interface UnityCatalogViewTranslator
{
    String translate(UnityCatalogView view);
}
