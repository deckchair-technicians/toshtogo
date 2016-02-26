#!/bin/sh
# Build script

cd server
lein midje
cd ../client
lein midje
