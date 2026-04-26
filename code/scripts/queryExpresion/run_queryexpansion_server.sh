#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/queryexpansion.env"
VENV_PYTHON="${SCRIPT_DIR}/.venv/bin/python3"

# Load environment variables
if [[ -f "${ENV_FILE}" ]]; then
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
fi

# Check API key
if [[ -z "${API_KEY:-}" ]]; then
  echo "API_KEY is not set. Export it or write it to ${ENV_FILE}." >&2
  exit 1
fi

# Select Python interpreter
PYTHON_BIN="python3"
if [[ -x "${VENV_PYTHON}" ]]; then
  PYTHON_BIN="${VENV_PYTHON}"
fi

# Run the server
exec "${PYTHON_BIN}" "${SCRIPT_DIR}/queryexpansion_server.py" "$@"