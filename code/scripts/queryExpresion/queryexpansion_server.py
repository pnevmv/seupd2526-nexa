#!/usr/bin/env python3
import argparse
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import google.generativeai as genai


def parse_args():
    parser = argparse.ArgumentParser(
        description="Serve Gemini-based query expansion over a local HTTP endpoint."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8001)
    parser.add_argument("--model-id", default="gemini-1.5-flash")
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--api-key-env-var", default="API_KEY",
                        help="Env var containing the Gemini API key.")
    return parser.parse_args()


def build_model(args):
    api_key = os.getenv(args.api_key_env_var)
    if not api_key:
        raise ValueError("API key not found in environment.")

    genai.configure(api_key=api_key)
    return genai.GenerativeModel(args.model_id)


def expand_query(model, query):
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

    response = model.generate_content(prompt)
    return response.text.strip()


class QueryExpansionHandler(BaseHTTPRequestHandler):
    model = None

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

            expanded = expand_query(self.model, query)

            self._send_json(200, {
                "original": query,
                "expanded": expanded
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

    QueryExpansionHandler.model = model

    server = ThreadingHTTPServer((args.host, args.port), QueryExpansionHandler)
    print(f"Query Expansion server listening on http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()