#!/bin/bash

# Run locally without minikube
sudo ifconfig lo0 alias 127.0.0.2 up
sudo ifconfig lo0 alias 127.0.0.3 up
ccm remove test
ccm create test -v 3.0.8 -n 3 -s
export CASS=localhost
docker stop etcd
docker rm etcd
docker stop jaegertracing
docker rm jaegertracing
docker run --name jaegertracing -d -p5775:5775/udp -p16686:16686 jaegertracing/all-in-one:latest
docker pull quay.io/coreos/etcd:v2.3.7
docker run \
  --detach \
  --name etcd \
  --publish 2379:2379 \
  quay.io/coreos/etcd:v2.3.7 \
  --listen-client-urls http://0.0.0.0:2379 \
  --advertise-client-urls http://192.168.99.100:2379
export ROLE=account
export SHARD_ROLE=account
sbt clean
sbt run
