#!/bin/bash
# Pemakaian: scripts/run-web.sh [port-http] [alamat-bind]   lalu buka http://localhost:8888
cd "$(dirname "$0")/.." || exit 1
java -cp out:lib/gson-2.15.0.jar client.NetQuizWebBridge "${1:-8888}" "${2:-127.0.0.1}"
