#!/usr/bin/env python3
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def parse_args():
    parser = argparse.ArgumentParser(
        description="Serve google/translategemma-4b-it over a local HTTP translation endpoint."
    )
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8008)
    parser.add_argument("--model-id", default="google/translategemma-4b-it")
    parser.add_argument("--device", default="auto",
                        help="Transformers pipeline device, e.g. auto, cuda, cpu, cuda:0")
    parser.add_argument("--dtype", default="auto",
                        help="Torch dtype: auto, bfloat16, float16, float32")
    parser.add_argument("--max-new-tokens", type=int, default=256)
    parser.add_argument("--hf-token-env-var", default="HF_TOKEN",
                        help="Env var containing a Hugging Face token if gated access requires it.")
    return parser.parse_args()


def resolve_torch_dtype(dtype_name):
    if dtype_name == "auto":
        return None

    import torch

    mapping = {
        "bfloat16": torch.bfloat16,
        "float16": torch.float16,
        "float32": torch.float32,
    }
    if dtype_name not in mapping:
        raise ValueError(f"Unsupported dtype: {dtype_name}")
    return mapping[dtype_name]


def build_pipeline(args):
    import os
    from transformers import pipeline

    kwargs = {"model": args.model_id}

    torch_dtype = resolve_torch_dtype(args.dtype)
    if torch_dtype is not None:
        kwargs["dtype"] = torch_dtype

    if args.device != "auto":
        kwargs["device"] = args.device

    token = os.getenv(args.hf_token_env_var)
    if token:
        kwargs["token"] = token

    return pipeline("image-text-to-text", **kwargs)


def extract_translation(output):
    if not output:
        raise ValueError("Empty generation output.")

    generated_text = output[0].get("generated_text")
    if isinstance(generated_text, list) and generated_text:
        last_message = generated_text[-1]
        if isinstance(last_message, dict):
            content = last_message.get("content")
            if isinstance(content, str) and content.strip():
                return content.strip()
    if isinstance(generated_text, str) and generated_text.strip():
        return generated_text.strip()

    raise ValueError(f"Unexpected TranslateGemma output format: {output}")


class TranslateGemmaHandler(BaseHTTPRequestHandler):
    generator = None
    max_new_tokens = 256

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404, "Not found")
            return

        self._send_json(200, {"status": "ok"})

    def do_POST(self):
        if self.path != "/translate":
            self.send_error(404, "Not found")
            return

        try:
            content_length = int(self.headers.get("Content-Length", "0"))
            raw_body = self.rfile.read(content_length)
            payload = json.loads(raw_body.decode("utf-8"))

            source_lang_code = payload["source_lang_code"]
            target_lang_code = payload["target_lang_code"]
            text = payload["text"]

            messages = [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "source_lang_code": source_lang_code,
                            "target_lang_code": target_lang_code,
                            "text": text,
                        }
                    ],
                }
            ]

            output = self.generator(text=messages, max_new_tokens=self.max_new_tokens)
            translation = extract_translation(output)
            self._send_json(200, {"translation": translation})
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
    generator = build_pipeline(args)

    TranslateGemmaHandler.generator = generator
    TranslateGemmaHandler.max_new_tokens = args.max_new_tokens

    server = ThreadingHTTPServer((args.host, args.port), TranslateGemmaHandler)
    print(f"TranslateGemma server listening on http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
