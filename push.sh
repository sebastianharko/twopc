#!/bin/sh
sbt clean
sbt docker:publishLocal
docker tag 2pc:$VERSION sebastianharko/2pc:$VERSION
docker push sebastianharko/2pc:$VERSION
