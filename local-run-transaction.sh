#!/bin/sh
set -e
export CLUSTERING_PORT=2552
export ROLE=COORDINATOR
export SHARD_ROLE=COORDINATOR
export CASS=localhost
sbt run