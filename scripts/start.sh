#!/usr/bin/env sh
# Запуск стенда одной командой: ./scripts/start.sh
set -eu
cd "$(dirname "$0")/.."

fail() {
  for line in "$@"; do echo "$line" >&2; done
  exit 1
}

command -v docker >/dev/null 2>&1 || fail "Нужен Docker с Compose."
docker compose version >/dev/null 2>&1 || fail "Нужен Docker Compose v2 (команда docker compose)."
command -v curl >/dev/null 2>&1 || fail "Нужен curl для проверки health."
docker info >/dev/null 2>&1 || fail "Docker daemon недоступен: запусти Docker Desktop или dockerd."

project=${COMPOSE_PROJECT_NAME:-api-security-lab}

if [ ! -f .env ]; then
  data_volume=$(docker volume ls -q \
    --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.volume=control-data")
  if [ -n "$data_volume" ]; then
    fail "Есть volume с данными БД ($data_volume), но нет .env: прежние пароли не восстановить." \
      "Верни прежний .env или удали данные: docker compose down --volumes"
  fi
  command -v openssl >/dev/null 2>&1 || fail "Нужен openssl для генерации секретов."

  token=$(openssl rand -hex 32)
  admin=$(openssl rand -hex 32)
  control=$(openssl rand -hex 32)
  (
    umask 077
    sed -e "s/replace-with-local-random-token/$token/" \
        -e "s/replace-with-local-admin-password/$admin/" \
        -e "s/replace-with-local-control-password/$control/" \
        .env.example > .env
  )
  echo "Создан .env со случайными секретами."
fi

if grep -q 'replace-with-' .env; then
  fail "В .env остались заглушки replace-with-*." \
    "Замени их на случайные значения (openssl rand -hex 32) или удали .env, чтобы скрипт создал его заново."
fi

docker compose up -d --build --wait
control_addr=$(docker compose port lab-control 8080)
curl --fail --silent --show-error "http://$control_addr/health"
echo
echo "Стенд запущен, Control: http://$control_addr. Остановить: docker compose down"
