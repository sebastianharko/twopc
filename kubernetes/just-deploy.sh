#!/bin/bash
echo "Deploying etcd and cassandra"
kubectl create -f etcd.yaml
kubectl create -f statsd.yaml
kubectl create -f cassandra-service.yaml
kubectl create -f cassandra-statefulset.yaml
sleep 80
echo "Deploying akka cluster"
kubectl create -f akka-coordinators.yaml
sleep 20
kubectl create -f akka-accounts.yaml
kubectl create -f akka-http.yaml
slepe 10
kubectl create -f akka-service.yaml
