#!/usr/bin/env bash
set -e

echo "======================================================="
echo " 🧹 APM & Continuous Profiling Platform Cleanup Utility "
echo "======================================================="

# 1. Terminate running background tasks
echo "[1/4] Killing running Java & Node processes..."
pkill -f "apm-collector-core" || true
pkill -f "apm-synthetic-agent" || true
pkill -f "next" || true
fuser -k 8080/tcp 2>/dev/null || true
fuser -k 8081/tcp 2>/dev/null || true
fuser -k 3000/tcp 2>/dev/null || true

# 2. Remove temporary & duplicate files
echo "[2/4] Removing temporary files & duplicate configs..."
rm -f docker/clickhouse/users.d/default-user.xml
rm -f repomix-output.xml *.log
rm -rf apm-web-ui/.next apm-web-ui/out apm-web-ui/tsconfig.tsbuildinfo

# 3. Stop Gradle daemons and wipe build artifacts
echo "[3/4] Cleaning Gradle build targets..."
./gradlew --stop || true
./gradlew clean --no-daemon

# 4. Docker cleanup prompt
if [ "$1" == "--all" ] || [ "$1" == "-a" ]; then
    echo "[4/4] Purging Docker containers and persistent volumes..."
    docker compose down -v --remove-orphans
else
    echo "[4/4] Stopping Docker containers (data volumes preserved)..."
    docker compose down --remove-orphans
    echo "      (Pass '--all' to also wipe ClickHouse & Redis volumes)"
fi

echo "======================================================="
echo " ✔ Project workspace is completely clean!"
echo "======================================================="
