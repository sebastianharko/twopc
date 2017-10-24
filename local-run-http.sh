#!/bin/bash
set -e
export CLUSTERING_PORT=2553
export ROLE=HTTP
export SHARD_ROLE=''
export CASS=localhost
sbt run