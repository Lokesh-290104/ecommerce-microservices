#!/usr/bin/env bash
# Build every service jar with the Maven Wrapper, then build the Docker images.
#
# Works from Git Bash on Windows (JDK on Windows, Docker inside WSL), from WSL or Linux
# with a JDK 21, and in CI. Extra arguments go to Maven, e.g. scripts/build.sh -DskipTests
# (compose always rebuilds all 4 images from whatever jars are in */target).
set -euo pipefail
cd "$(dirname "$0")/.."

./mvnw -B package "$@"

if docker info >/dev/null 2>&1; then
    docker compose build
elif command -v wsl.exe >/dev/null 2>&1 && win_dir=$(pwd -W 2>/dev/null); then
    # Git Bash on Windows: Docker lives in WSL. `pwd -W` exists only in Git Bash/MSYS,
    # so inside WSL itself (where wsl.exe is also on PATH) this branch is skipped.
    wsl.exe --cd "$win_dir" docker compose build
else
    echo "No usable Docker daemon (tried docker, and wsl.exe from Git Bash)." >&2
    if command -v docker >/dev/null 2>&1; then
        docker info >&2 || true   # show why the local daemon is unusable
    fi
    exit 1
fi
