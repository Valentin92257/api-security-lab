#!/usr/bin/env sh
# Запуск стенда из исходников: ./scripts/start.sh
set -eu
cd "$(dirname "$0")/.."
LAB_DIR=. LAB_LOCAL=1 exec sh scripts/install.sh
