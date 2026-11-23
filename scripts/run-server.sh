#!/bin/bash
cd "$(dirname "$0")/.." || exit 1
java -cp out:lib/gson-2.15.0.jar server.NetQuizServer
