#!/usr/bin/env sh
# Установка и запуск стенда одной командой:
#   curl -fsSL https://raw.githubusercontent.com/Valentin92257/api-security-lab/main/scripts/install.sh | sh
set -eu

fail() {
  for line in "$@"; do printf '%s\n' "$line" >&2; done
  exit 1
}

fetch_file() {
  tmp="${2}.tmp.$$"
  curl -fsSL --max-time 30 -o "$tmp" "$1" || { rm -f "$tmp"; fail "Не удалось скачать $1"; }
  mv "$tmp" "$2"
}

gen_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  elif [ -r /dev/urandom ] && command -v od >/dev/null 2>&1; then
    od -An -tx1 -N32 /dev/urandom | tr -d ' \t\n\r'
  else
    fail "Нужен openssl (или /dev/urandom + od) для генерации секретов."
  fi
}

upsert_env_key() {
  file=$1 key=$2 value=$3 tmp="${1}.tmp.$$" cr=$(printf '\r') found=0
  (
    umask 077
    : > "$tmp"
    while IFS= read -r line || [ -n "$line" ]; do
      line=${line%"$cr"}
      case "$line" in
        "${key}="*) printf '%s=%s\n' "$key" "$value" >> "$tmp"; found=1 ;;
        *) printf '%s\n' "$line" >> "$tmp" ;;
      esac
    done < "$file"
    [ "$found" -eq 1 ] || printf '%s=%s\n' "$key" "$value" >> "$tmp"
  )
  mv "$tmp" "$file"
}

create_env_from_example() {
  src=$1 dst=$2 tmp="${2}.tmp.$$" cr=$(printf '\r')
  (
    umask 077
    while IFS= read -r line || [ -n "$line" ]; do
      line=${line%"$cr"}
      case "$line" in
        *=replace-with-*) printf '%s=%s\n' "${line%%=*}" "$(gen_secret)" ;;
        LAB_CONTROL_PORT=*) printf 'LAB_CONTROL_PORT=%s\n' "${LAB_CONTROL_PORT:-${line#*=}}" ;;
        *) printf '%s\n' "$line" ;;
      esac
    done < "$src" > "$tmp"
  )
  mv "$tmp" "$dst"
}

append_missing_env_keys() {
  src=$1 dst=$2 cr=$(printf '\r') added=""
  while IFS= read -r line || [ -n "$line" ]; do
    line=${line%"$cr"}
    case "$line" in
      ''|'#'*) continue ;;
      *=*)
        key=${line%%=*}
        if ! grep -q "^${key}=" "$dst"; then
          val=${line#*=}
          case "$val" in replace-with-*) val=$(gen_secret) ;; esac
          (umask 077; printf '%s=%s\n' "$key" "$val" >> "$dst")
          added="${added} ${key}"
        fi
        ;;
    esac
  done < "$src"
  [ -z "$added" ] || echo "В .env добавлены новые переменные из .env.example:${added}"
}

main() {
  repo=${LAB_REPO:-Valentin92257/api-security-lab}
  ref=${LAB_REF:-main}
  dir=${LAB_DIR:-api-security-lab}
  local_mode=${LAB_LOCAL:-0}
  skip_build=${LAB_SKIP_BUILD:-0}

  explicit_version=${LAB_VERSION:-}
  if [ -z "$explicit_version" ]; then
    case "$ref" in v[0-9]*) explicit_version=${ref#v} ;; esac
  fi

  command -v docker >/dev/null 2>&1 || fail "Нужен Docker с Compose."
  docker compose version >/dev/null 2>&1 || fail "Нужен Docker Compose v2 (команда docker compose)."
  command -v curl >/dev/null 2>&1 || fail "Нужен curl для скачивания файлов и проверки health."
  docker info >/dev/null 2>&1 || fail "Docker daemon недоступен: запусти Docker Desktop или dockerd."

  [ "$local_mode" = "1" ] || mkdir -p "$dir"
  cd "$dir"

  if [ "$local_mode" != "1" ]; then
    base_url="https://raw.githubusercontent.com/${repo}/${ref}"
    echo "Скачиваю compose.yaml и .env.example (${repo}@${ref}) в $(pwd)..."
    fetch_file "${base_url}/compose.yaml" "compose.yaml"
    fetch_file "${base_url}/.env.example" ".env.example"
  else
    [ -f compose.yaml ] && [ -f .env.example ] || fail "В $(pwd) нет compose.yaml или .env.example."
  fi

  project=${COMPOSE_PROJECT_NAME:-api-security-lab}
  if [ ! -f .env ]; then
    data_volume=$(docker volume ls -q \
      --filter "label=com.docker.compose.project=$project" \
      --filter "label=com.docker.compose.volume=control-data")
    if [ -n "$data_volume" ]; then
      fail "Есть volume с данными БД ($data_volume), но нет .env: прежние пароли не восстановить." \
        "Верни прежний .env или удали данные: docker compose down --volumes"
    fi
    create_env_from_example .env.example .env
    echo "Создан .env со случайными секретами."
  else
    append_missing_env_keys .env.example .env
  fi

  [ -z "$explicit_version" ] || upsert_env_key .env "LAB_VERSION" "$explicit_version"
  [ -z "${LAB_IMAGE_PREFIX:-}" ] || upsert_env_key .env "LAB_IMAGE_PREFIX" "$LAB_IMAGE_PREFIX"

  if grep -q 'replace-with-' .env; then
    fail "В .env остались заглушки replace-with-*." \
      "Замени их на случайные значения (openssl rand -hex 32) или удали .env, чтобы скрипт создал его заново."
  fi

  if [ "$local_mode" = "1" ]; then
    if [ "$skip_build" = "1" ]; then
      docker compose up -d --wait
    else
      docker compose up -d --build --wait
    fi
  else
    docker compose pull
    docker compose up -d --wait
  fi

  control_addr=$(docker compose port lab-control 8080 | head -n 1 | tr -d '\r')
  [ -n "$control_addr" ] || fail "Не удалось определить порт lab-control."
  curl -fsS --max-time 5 "http://${control_addr}/health"
  echo
  if [ "$local_mode" = "1" ]; then
    echo "Стенд запущен, Control: http://${control_addr}. Остановить: docker compose down"
  else
    echo "Стенд запущен в $(pwd)"
    echo "Control:    http://${control_addr}"
    echo "Остановить: cd \"$(pwd)\" && docker compose down"
    echo "Обновить:   cd \"$(pwd)\" && docker compose pull && docker compose up -d --wait"
    echo "Удалить БД: cd \"$(pwd)\" && docker compose down --volumes"
  fi
}

main "$@"
