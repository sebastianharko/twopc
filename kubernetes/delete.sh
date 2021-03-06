#!/bin/sh
kubectl delete service grafana
kubectl delete service sd
kubectl delete service etcd
kubectl delete service cassandra
kubectl delete service twopc
kubectl delete service twopc-akkamgmt
kubectl delete service z
kubectl delete service http-service
kubectl delete deployment twopc
kubectl delete deployment cassandra
kubectl delete deployment etcd
kubectl delete deployment ggs
kubectl delete deployment web
kubectl delete deployment coordinator
kubectl delete deployment coordinators
kubectl delete deployment account
kubectl delete deployment accounts
kubectl delete deployment http-deployment
kubectl delete StorageClass fast
kubectl delete StatefulSet cassandra
kubectl delete PersistentVolumeClaim cassandra-data-cassandra-0
kubectl delete PersistentVolumeClaim cassandra-data-cassandra-1
kubectl delete PersistentVolumeClaim cassandra-data-cassandra-2
