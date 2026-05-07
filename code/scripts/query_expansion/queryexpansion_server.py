#!/usr/bin/env python3
import argparse
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Lock

import google.generativeai as genai


def parse_args():
    parser = argparse.ArgumentParser(
        description="Serve Gemini-based query expansion over a local HTTP endpoint."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8001)
    parser.add_argument("--model-id", default="gemini-1.5-flash")
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--cache-file",
                        help="JSON cache file for expanded queries. Defaults to queryexpansion_cache.json next to this script.")
    parser.add_argument("--api-key-env-var", default="API_KEY",
                        help="Env var containing the Gemini API key.")
    return parser.parse_args()


def build_model(args):
    api_key = os.getenv(args.api_key_env_var)
    if not api_key:
        raise ValueError("API key not found in environment.")

    genai.configure(api_key=api_key)
    return genai.GenerativeModel(args.model_id)


class QueryExpansionCache:

    def __init__(self, path, model_id):
        self.path = path
        self.model_id = model_id
        self.lock = Lock()
        self.entries = self._load()

    def get(self, query):
        with self.lock:
            entry = self.entries.get(query)

        if not isinstance(entry, dict):
            return None
        if entry.get("model_id") != self.model_id:
            return None

        expanded = entry.get("expanded")
        if isinstance(expanded, str) and expanded.strip():
            return expanded.strip()
        return None

    def put(self, query, expanded):
        if not isinstance(expanded, str) or not expanded.strip():
            return

        with self.lock:
            self.entries[query] = {
                "original": query,
                "expanded": expanded.strip(),
                "model_id": self.model_id,
            }
            self._write()

    def _load(self):
        if self.path is None or not self.path.exists():
            return {}

        with self.path.open("r", encoding="utf-8") as handle:
            data = json.load(handle)

        if not isinstance(data, dict):
            raise ValueError(f"Query expansion cache must contain a JSON object: {self.path}")
        return data

    def _write(self):
        if self.path is None:
            return

        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = self.path.with_suffix(self.path.suffix + ".tmp")
        with tmp_path.open("w", encoding="utf-8") as handle:
            json.dump(self.entries, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        os.replace(tmp_path, self.path)


def expand_query(model, query, max_new_tokens):
    prompt = f"""
You are a query expansion module for a search engine (Lucene/BM25).

Task:
Transform the input text into clean search keywords.

Rules:
- Extract main concepts
- Remove noise (mentions, emojis, opinions)
- Add synonyms and related terms
- Normalize terminology (COVID-19 → coronavirus)
- Return ONLY keywords
- Space-separated
- Max 10–15 terms

Input:
{query}

Output:
"""

    response = model.generate_content(
        prompt,
        generation_config={
            "max_output_tokens": max_new_tokens,
            "temperature": 0.0,
        },
    )
    return response.text.strip()


class QueryExpansionHandler(BaseHTTPRequestHandler):
    model = None
    cache = None
    max_new_tokens = 256

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404, "Not found")
            return

        self._send_json(200, {"status": "ok"})

    def do_POST(self):
        if self.path != "/expand":
            self.send_error(404, "Not found")
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(content_length)
            payload = json.loads(raw_body.decode("utf-8"))

            query = payload["query"]
            cached = self.cache.get(query) if self.cache is not None else None
            if cached is not None:
                self._send_json(200, {
                    "original": query,
                    "expanded": cached,
                    "cached": True
                })
                return

            expanded = expand_query(self.model, query, self.max_new_tokens)
            if self.cache is not None:
                self.cache.put(query, expanded)

            self._send_json(200, {
                "original": query,
                "expanded": expanded,
                "cached": False
            })

        except KeyError as exc:
            self._send_json(400, {"error": f"Missing field: {exc}"})
        except Exception as exc:
            self._send_json(500, {"error": str(exc)})

    def log_message(self, fmt, *args):
        return

    def _send_json(self, status_code, body):
        encoded_body = json.dumps(body).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded_body)))
        self.end_headers()
        self.wfile.write(encoded_body)


def main():
    args = parse_args()
    model = build_model(args)
    cache_file = Path(args.cache_file) if args.cache_file else Path(__file__).with_name("queryexpansion_cache.json")

    QueryExpansionHandler.model = model
    QueryExpansionHandler.cache = QueryExpansionCache(cache_file, args.model_id)
    QueryExpansionHandler.max_new_tokens = args.max_new_tokens

    server = ThreadingHTTPServer((args.host, args.port), QueryExpansionHandler)
    print(f"Query Expansion server listening on http://{args.host}:{args.port}")
    print(f"Query expansion cache: {cache_file}")
    server.serve_forever()


if __name__ == "__main__":
    main()
