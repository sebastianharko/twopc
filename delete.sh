#!/bin/sh
kubectl delete service etcd
kubectl delete service cassandra
kubectl delete service twopc
kubectl delete service twopc-akkamgmt
kubectl delete deployment twopc
kubectl delete deployment cassandra
kubectl delete deployment etcd
