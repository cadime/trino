Trino is a distributed SQL query engine

This is a slimmed-down server package for the Unity Catalog fork. It layers
only the Delta Lake (with Unity Catalog metastore support), PostgreSQL and
Spark-compatibility-function plugins on top of trino-server-core, instead of
the ~48 connectors shipped by the full trino-server package.

Find more information on the Trino website at https://trino.io/
