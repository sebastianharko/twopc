#!/bin/sh
# sbt clean
# sbt docker:publishLocal
# docker tag 2pc:$VERSION sebastianharko/2pc:$VERSION
# docker push sebastianharko/2pc:$VERSION
sleep 10
echo "Deleting services and deployments"
kubectl delete service etcd
kubectl delete service cassandra
kubectl delete service twopc
kubectl delete service twopc-akkamgmt
kubectl delete deployment twopc
kubectl delete deployment cassandra
kubectl delete deployment etcd
echo "Deploying etcd and cassandra"
kubectl create -f etcd.yaml
kubectl create -f cassandra.yaml
sleep 30
echo "Deploying akka cluster"
kubectl create -f akka.yaml
