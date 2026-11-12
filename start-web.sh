#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

echo "=== [1/4] Khoi chay Mosquitto, PostgreSQL va Backend qua Docker Compose ==="
if ip -4 addr show 2>/dev/null | grep -q "172\.21\.0\.1/"; then
  echo " -> Phat hien xung dot dai IP 172.21.0.1 voi mang Wi-Fi truong, dang tao lai mang Docker..."
  docker compose down 2>/dev/null || true
  docker network prune -f >/dev/null 2>&1 || true
fi
docker compose up -d --build

echo "=== [2/4] Cho Backend tao bang du lieu (Flyway migration) ==="
for i in {1..30}; do
  if docker compose exec -T database psql -U airsense -d airsense -tAc "SELECT to_regclass('public.devices');" 2>/dev/null | grep -q "devices"; then
    break
  fi
  sleep 2
done

echo "=== [3/4] Kiem tra va dang ky thiet bi AIRSENSE-001 (bootId: b7f3a901) ==="
DEVICE_EXISTS=$(docker compose exec -T database psql -U airsense -d airsense -tAc "SELECT count(*) FROM devices WHERE device_id='AIRSENSE-001';" 2>/dev/null | tr -d '[:space:]')
if [ "$DEVICE_EXISTS" = "0" ]; then
  docker compose exec -T database psql -U airsense -d airsense -v ON_ERROR_STOP=1 < backend/provision/simulator.sql
  docker compose restart backend
  echo " -> Da dang ky thiet bi AIRSENSE-001 thanh cong!"
else
  echo " -> Thiet bi AIRSENSE-001 da co san trong Database."
fi

echo "=== [4/4] Cai dat & Khoi chay giao dien Web Frontend (http://127.0.0.1:5173) ==="
cd frontend
if [ ! -x "node_modules/.bin/vite" ]; then
  npm ci
fi
npm run dev
