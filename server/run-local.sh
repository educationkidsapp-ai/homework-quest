#!/usr/bin/env bash
# Runs the API on in-memory H2 (no PostgreSQL, no Firebase) with the LLM from .env.
#   ./server/run-local.sh            # real DeepSeek (needs DEEPSEEK_API_KEY in .env)
#   LLM_PROVIDER=fake ./server/run-local.sh   # no model calls at all
set -euo pipefail
cd "$(dirname "$0")"
[ -f ../.env ] && { set -a; source ../.env; set +a; }
export SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE:-h2} ADMIN_EMAIL=${ADMIN_EMAIL:-admin@quest.local} ADMIN_PASSWORD=${ADMIN_PASSWORD:-admin1234}
export PUBLIC_URL=${PUBLIC_URL:-http://10.0.2.2:8080}     # the Android emulator's address for this machine
./mvnw -q -B package -DskipTests
exec java -jar target/server.jar
