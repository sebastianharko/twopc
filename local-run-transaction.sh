#!/bin/sh
set -e
export CLUSTERING_PORT=2552
export ROLE=coordinator
export SHARD_ROLE=coordinator
export CASS=localhost
sbt run