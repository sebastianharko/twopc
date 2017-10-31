#!/bin/bash
echo "Deploying etcd and cassandra"
kubectl create -f etcd.yaml
kubectl create -f statsd.yaml
kubectl create -f cassandra-service.yaml
kubectl create -f cassandra-statefulset.yaml
sleep 60
echo "Deploying akka cluster"
kubectl create -f akka-accounts.yaml
sleep 15
kubectl scale --replicas=8 deployments/accounts
sleep 15
kubectl create -f akka-coordinators.yaml
sleep 15
kubectl create -f http.yaml
sleep 15
kubectl create -f http-service.yaml
