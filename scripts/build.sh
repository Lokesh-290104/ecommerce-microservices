#!/usr/bin/env bash
# Build every service jar with the Maven Wrapper, then build the Docker images.
#
# Works from Git Bash on Windows (JDK on Windows, Docker inside WSL), from WSL or Linux
# with a JDK 21, and in CI. Extra arguments go to Maven, e.g. scripts/build.sh -DskipTests
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -B package "$@"

if docker info >/dev/null 2>&1; then
    docker compose build
elif command -v wsl.exe >/dev/null 2>&1; then
    # Git Bash on Windows: Docker lives in WSL. `pwd -W` gives the Windows path.
    wsl.exe --cd "$(pwd -W)" docker compose build
else
    echo "No Docker daemon found (tried docker and wsl.exe)." >&2
    exit 1
fi
