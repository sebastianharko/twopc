#!/bin/bash
echo "Deploying etcd and cassandra"
kubectl create -f etcd.yaml
kubectl create -f statsd.yaml
kubectl create -f cassandra-service.yaml
kubectl create -f cassandra-statefulset.yaml
sleep 420
echo "Deploying akka cluster"
kubectl create -f akka-accounts.yaml
sleep 30
kubectl scale --replicas=16 deployments/accounts
sleep 30
kubectl create -f akka-coordinators.yaml
sleep 30
kubectl create -f http.yaml
sleep 10
kubectl create -f http-service.yaml
