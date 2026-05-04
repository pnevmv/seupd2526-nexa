#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/translategemma.env"
VENV_PYTHON="${SCRIPT_DIR}/.venv/bin/python3"

if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

if [[ -z "${HF_TOKEN:-}" ]]; then
  echo "HF_TOKEN is not set. Export it or write it to ${ENV_FILE}." >&2
  exit 1
fi

PYTHON_BIN="python3"
if [[ -x "${VENV_PYTHON}" ]]; then
  PYTHON_BIN="${VENV_PYTHON}"
fi

exec "${PYTHON_BIN}" "${SCRIPT_DIR}/inference_server.py" "$@"
