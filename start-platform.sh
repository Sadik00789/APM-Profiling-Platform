#!/usr/bin/env bash
set -e

echo "=== [1/5] Stopping old background tasks & Gradle daemons ==="
pkill -f "apm-collector-core" || true
pkill -f "apm-synthetic-agent" || true
pkill -f "next" || true
./gradlew --stop || true

echo "=== [2/5] Starting Low-Memory ClickHouse & Redis ==="
docker compose up -d

echo "=== [3/5] Compiling JARs (No Daemons) ==="
./gradlew bootJar -x test --no-daemon

echo "=== [4/5] Launching Java Services (Strict 384M / 192M Limits) ==="
COLLECTOR_JAR=$(find apm-collector-core/build/libs -name "*.jar" -not -name "*plain.jar" | head -n 1)
AGENT_JAR=$(find apm-synthetic-agent/build/libs -name "*.jar" -not -name "*plain.jar" | head -n 1)

# Ingestion Collector
java -Xms64m -Xmx384m \
     -XX:+UseSerialGC \
     -jar "$COLLECTOR_JAR" > collector.log 2>&1 &
echo "Collector running (PID: $!)..."

# Synthetic Chaos Agent
java -Xms32m -Xmx192m \
     -XX:+UseSerialGC \
     -jar "$AGENT_JAR" > agent.log 2>&1 &
echo "Chaos Agent running (PID: $!)..."

echo "Waiting 3 seconds for backend services to initialize..."
sleep 3

echo "=== [5/5] Launching Next.js UI (Capped Node Heap) ==="
cd apm-web-ui
export NODE_OPTIONS="--max-old-space-size=256"
npm run dev
