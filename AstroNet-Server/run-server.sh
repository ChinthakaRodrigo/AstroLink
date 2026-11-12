#!/usr/bin/env bash
# Usage: ./run-server.sh [tcpPort] [udpPort]     (defaults 5000 / 5001)
cd "$(dirname "$0")"
java -cp "out:lib/gson-2.13.2.jar" server.AstroServer "$@"
