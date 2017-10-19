#!/bin/bash
echo "Deploying etcd and cassandra"
kubectl create -f etcd.yaml
kubectl create -f statsd.yaml
kubectl create -f cassandra-service.yaml
kubectl create -f cassandra-statefulset.yaml
sleep 100
echo 1 / 5
sleep 100
echo 2 / 5
sleep 100
echo 3 / 5
sleep 100
echo 4 / 5
sleep 100
echo 5 / 5
echo "Deploying akka cluster"
kubectl create -f akka.yaml
sleep 100
kubectl scale --replicas=8 deployments/twopc
kubectl create -f akka-service.yaml
