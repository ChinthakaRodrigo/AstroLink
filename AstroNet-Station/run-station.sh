#!/usr/bin/env bash
# Usage: ./run-station.sh [stationId] [serverIp] [tcpPort] [udpPort]
#   e.g. ./run-station.sh ASTRO-02 192.168.1.10
cd "$(dirname "$0")"
java -cp "out:lib/gson-2.13.2.jar" station.AstroStation "$@"
