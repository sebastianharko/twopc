#!/bin/bash
echo "Deploying etcd and cassandra"
kubectl create -f etcd.yaml
kubectl create -f statsd.yaml
kubectl create -f cassandra-service.yaml
kubectl create -f cassandra-statefulset.yaml
sleep 300
echo "Deploying akka cluster"
kubectl create -f akka.yaml
sleep 100
kubectl scale --replicas=7 deployments/twopc
kubectl create -f akka-service.yaml
