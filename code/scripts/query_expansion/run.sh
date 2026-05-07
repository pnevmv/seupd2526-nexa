#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/queryexpansion.env"
VENV_PYTHON="${SCRIPT_DIR}/../.venv/bin/python3"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

if [[ -z "${API_KEY:-}" ]]; then
  echo "API_KEY is not set. Export it or write it to ${ENV_FILE}." >&2
  exit 1
fi

PYTHON_BIN="python3"
if [[ -x "${VENV_PYTHON}" ]]; then
  PYTHON_BIN="${VENV_PYTHON}"
fi

SERVER_ARGS=()
[[ -n "${MODEL:-}" ]]      && SERVER_ARGS+=(--model-id "${MODEL}")
[[ -n "${PORT:-}" ]]       && SERVER_ARGS+=(--port "${PORT}")
[[ -n "${CACHE_FILE:-}" ]] && SERVER_ARGS+=(--cache-file "${CACHE_FILE}")

exec "${PYTHON_BIN}" "${SCRIPT_DIR}/queryexpansion_server.py" "${SERVER_ARGS[@]}" "$@"
