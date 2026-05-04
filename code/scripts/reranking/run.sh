#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_PYTHON="${SCRIPT_DIR}/../.venv/bin/python3"

PYTHON_BIN="python3"
if [[ -x "${VENV_PYTHON}" ]]; then
  PYTHON_BIN="${VENV_PYTHON}"
fi

exec "${PYTHON_BIN}" "${SCRIPT_DIR}/reranking_server.py" "$@"
