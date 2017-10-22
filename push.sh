#!/bin/sh
sudo sbt clean
sbt docker:publishLocal
sudo docker tag 2pc:$VERSION sebastianharko/2pc:$VERSION
sudo docker push sebastianharko/2pc:$VERSION
