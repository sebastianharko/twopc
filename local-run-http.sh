#!/bin/bash
set -e
export CLUSTERING_PORT=2553
export ROLE=http
export SHARD_ROLE=''
export CASS=localhost
export POD_IP=127.0.0.1
sbt run

