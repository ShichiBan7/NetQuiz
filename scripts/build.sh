#!/bin/bash
cd "$(dirname "$0")/.." || exit 1
rm -rf out && mkdir out && javac -cp lib/gson-2.15.0.jar -d out server/*.java client/*.java && echo "Build OK -> out/"
