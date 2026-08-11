#!/usr/bin/env bash
# 启动 P1-06 Edge Relay 部署栈并做健康/指标冒烟
set -euo pipefail
cd "$(dirname "$0")"

docker compose up -d --build

for i in $(seq 1 30); do
  status=$(curl -fsS http://localhost:8091/actuator/health 2>/dev/null || true)
  if [[ "$status" == *'"UP"'* ]]; then
    echo "edge-relay health: UP (after ${i}s)"
  else
    sleep 1
  fi
done

echo "--- edge_ metrics (prometheus) ---"
curl -fsS http://localhost:8091/actuator/prometheus | grep '^edge_' || echo "edge_* 指标尚未上报"

docker compose ps