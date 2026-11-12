#!/usr/bin/env bash
# Compiles this project into ./out (Linux / macOS / Git Bash). Requires JDK 17+.
set -e
cd "$(dirname "$0")"
rm -rf out
mkdir -p out
javac --release 17 -encoding UTF-8 -d out -cp lib/gson-2.13.2.jar $(find src -name "*.java")
echo "Compilation successful -> out/"
