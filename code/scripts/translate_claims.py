#!/usr/bin/env python3
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Optional


LANG_SPLIT_PATTERN = re.compile(r"^(de|en|fr)_(train|dev)\.json$", re.IGNORECASE)
DEFAULT_SERVICE_URL = "http://127.0.0.1:8081/translate"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Translate claim texts through the local TranslateGemma HTTP server."
    )
    parser.add_argument("--claims-file", required=True, help="Input claims JSON file.")
    parser.add_argument("--output-file", required=True, help="Output translated claims JSON file.")
    parser.add_argument("--source-lang", help="Source language code. Defaults to inferring from file name.")
    parser.add_argument("--target-lang", default="en", help="Target language code. Defaults to en.")
    parser.add_argument("--service-url", default=DEFAULT_SERVICE_URL, help="TranslateGemma /translate URL.")
    parser.add_argument("--timeout", type=int, default=240, help="Per-request timeout in seconds.")
    parser.add_argument("--retries", type=int, default=2, help="Retries per failed translation.")
    parser.add_argument("--checkpoint-every", type=int, default=1, help="Write output every N translated records.")
    parser.add_argument("--limit", type=int, help="Translate only the first N records, for smoke tests.")
    parser.add_argument("--overwrite", action="store_true", help="Ignore any existing output file.")
    return parser.parse_args()


def infer_source_lang(path: Path) -> Optional[str]:
    match = LANG_SPLIT_PATTERN.match(path.name)
    return match.group(1).lower() if match else None


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json_atomic(path: Path, records) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = path.with_suffix(path.suffix + ".tmp")
    with tmp_path.open("w", encoding="utf-8") as handle:
        json.dump(records, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(tmp_path, path)


def translate_text(service_url: str,
                   source_lang: str,
                   target_lang: str,
                   text: str,
                   timeout: int,
                   retries: int) -> str:
    payload = json.dumps({
        "source_lang_code": source_lang,
        "target_lang_code": target_lang,
        "text": text,
    }).encode("utf-8")

    request = urllib.request.Request(
        service_url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )

    last_error = None
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                body = response.read().decode("utf-8")
            data = json.loads(body)
            translation = data.get("translation")
            if not isinstance(translation, str) or not translation.strip():
                raise RuntimeError(f"Response did not contain a non-empty translation: {body}")
            return translation.strip()
        except (urllib.error.URLError, TimeoutError, RuntimeError, json.JSONDecodeError) as exc:
            last_error = exc
            if attempt >= retries:
                break
            time.sleep(2 ** attempt)

    raise RuntimeError(f"Translation failed after {retries + 1} attempt(s): {last_error}")


def is_completed(record, source_lang: str, target_lang: str) -> bool:
    return (
        record.get("translation_provider") == "gemma"
        and record.get("source_language") == source_lang
        and record.get("target_language") == target_lang
        and isinstance(record.get("original_text"), str)
        and isinstance(record.get("text"), str)
        and bool(record.get("text", "").strip())
    )


def main() -> int:
    args = parse_args()
    claims_file = Path(args.claims_file)
    output_file = Path(args.output_file)
    source_lang = args.source_lang or infer_source_lang(claims_file)

    if source_lang is None:
        print("Unable to infer source language. Pass --source-lang.", file=sys.stderr)
        return 2
    if source_lang == args.target_lang:
        print("Source and target language are the same; nothing to translate.", file=sys.stderr)
        return 2

    input_records = load_json(claims_file)
    if args.limit is not None:
        input_records = input_records[:args.limit]

    if output_file.exists() and not args.overwrite:
        output_records = load_json(output_file)
        if len(output_records) != len(input_records):
            print(
                f"Existing output has {len(output_records)} records but input has {len(input_records)}. "
                "Use --overwrite or a different --output-file.",
                file=sys.stderr,
            )
            return 2
    else:
        output_records = []
        for record in input_records:
            copied = dict(record)
            copied["original_text"] = record.get("text", "")
            output_records.append(copied)
        write_json_atomic(output_file, output_records)

    total = len(output_records)
    translated_this_run = 0
    started = time.time()

    for position, record in enumerate(output_records, start=1):
        if is_completed(record, source_lang, args.target_lang):
            continue

        original_text = record.get("original_text")
        if not isinstance(original_text, str):
            original_text = record.get("text", "")
            record["original_text"] = original_text

        if not original_text.strip():
            record["text"] = original_text
            record["translation_provider"] = "gemma"
            record["source_language"] = source_lang
            record["target_language"] = args.target_lang
            continue

        item_started = time.time()
        translation = translate_text(
            args.service_url,
            source_lang,
            args.target_lang,
            original_text,
            args.timeout,
            args.retries,
        )
        elapsed = time.time() - item_started

        record["text"] = translation
        record["translation_provider"] = "gemma"
        record["source_language"] = source_lang
        record["target_language"] = args.target_lang
        translated_this_run += 1

        if translated_this_run % max(1, args.checkpoint_every) == 0:
            write_json_atomic(output_file, output_records)

        rate = translated_this_run / max(1.0, time.time() - started)
        remaining = total - position
        eta = int(remaining / rate) if rate > 0 else 0
        print(
            f"[{position:5d}/{total}] index={record.get('index')} "
            f"{elapsed:5.1f}s translated_this_run={translated_this_run} eta={eta}s",
            flush=True,
        )

    write_json_atomic(output_file, output_records)
    print(f"Translated claims written to {output_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
