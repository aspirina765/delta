#!/usr/bin/env bash 


./connectors/sql-delta-import/target/scala-2.12/sql-delta-import_2.12-3.3.0-SNAPSHOT.jar


build/sbt compile

build/sbt package

find . -type f -name "*sql-delta-import*.jar"

./connectors/sql-delta-import/target/scala-2.12/sql-delta-import_2.12-3.3.0-SNAPSHOT.jar

build/sbt test

build/sbt spark/'testOnly org.apache.spark.sql.delta.optimize.OptimizeCompactionSQLSuite'

build/sbt spark/'testOnly *.OptimizeCompactionSQLSuite -- -z "optimize command: on partitioned table - all partitions"'





