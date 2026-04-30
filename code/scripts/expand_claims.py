#!/usr/bin/env python3
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


DEFAULT_SERVICE_URL = "http://127.0.0.1:8001/expand"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Expand claim texts through the local query expansion HTTP server."
    )
    parser.add_argument("--claims-file", required=True, help="Input claims JSON file.")
    parser.add_argument("--output-file", required=True, help="Output expanded claims JSON file.")
    parser.add_argument("--service-url", default=DEFAULT_SERVICE_URL, help="Query expansion /expand URL.")
    parser.add_argument("--timeout", type=int, default=240, help="Per-request timeout in seconds.")
    parser.add_argument("--retries", type=int, default=2, help="Retries per failed expansion.")
    parser.add_argument("--checkpoint-every", type=int, default=1, help="Write output every N expanded records.")
    parser.add_argument("--limit", type=int, help="Expand only the first N records, for smoke tests.")
    parser.add_argument("--overwrite", action="store_true", help="Ignore any existing output file.")
    return parser.parse_args()


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


def expand_text(service_url: str,
                text: str,
                timeout: int,
                retries: int) -> tuple[str, bool]:
    payload = json.dumps({"query": text}).encode("utf-8")

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
            expanded = data.get("expanded")
            if not isinstance(expanded, str):
                raise RuntimeError(f"Response did not contain an expanded field: {body}")
            return expanded.strip(), bool(data.get("cached", False))
        except (urllib.error.URLError, TimeoutError, RuntimeError, json.JSONDecodeError) as exc:
            last_error = exc
            if attempt >= retries:
                break
            time.sleep(2 ** attempt)

    raise RuntimeError(f"Query expansion failed after {retries + 1} attempt(s): {last_error}")


def combine_original_and_expanded(original_text: str, expanded_terms: str) -> str:
    original = original_text.strip()
    expanded = expanded_terms.strip()
    if not expanded or original.lower() == expanded.lower():
        return original
    return f"{original} {expanded}"


def is_completed(record) -> bool:
    return (
        record.get("query_expansion_provider") == "gemini"
        and isinstance(record.get("query_expansion_input_text"), str)
        and isinstance(record.get("query_expansion_terms"), str)
        and isinstance(record.get("text"), str)
        and bool(record.get("text", "").strip())
    )


def initialize_output_records(input_records):
    output_records = []
    for record in input_records:
        copied = dict(record)
        copied["query_expansion_input_text"] = record.get("text", "")
        output_records.append(copied)
    return output_records


def main() -> int:
    args = parse_args()
    claims_file = Path(args.claims_file)
    output_file = Path(args.output_file)

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
        output_records = initialize_output_records(input_records)
        write_json_atomic(output_file, output_records)

    total = len(output_records)
    expanded_this_run = 0
    service_cache_hits = 0
    started = time.time()

    for position, record in enumerate(output_records, start=1):
        if is_completed(record):
            continue

        input_text = record.get("query_expansion_input_text")
        if not isinstance(input_text, str):
            input_text = record.get("text", "")
            record["query_expansion_input_text"] = input_text

        if not input_text.strip():
            record["text"] = input_text
            record["query_expansion_terms"] = ""
            record["query_expansion_provider"] = "gemini"
            record["query_expansion_combined"] = True
            continue

        item_started = time.time()
        expanded_terms, served_from_cache = expand_text(
            args.service_url,
            input_text,
            args.timeout,
            args.retries,
        )
        elapsed = time.time() - item_started

        record["text"] = combine_original_and_expanded(input_text, expanded_terms)
        record["query_expansion_terms"] = expanded_terms
        record["query_expansion_provider"] = "gemini"
        record["query_expansion_combined"] = True
        record["query_expansion_service_url"] = args.service_url
        record["query_expansion_server_cached"] = served_from_cache
        expanded_this_run += 1
        if served_from_cache:
            service_cache_hits += 1

        if expanded_this_run % max(1, args.checkpoint_every) == 0:
            write_json_atomic(output_file, output_records)

        rate = expanded_this_run / max(1.0, time.time() - started)
        remaining = total - position
        eta = int(remaining / rate) if rate > 0 else 0
        print(
            f"[{position:5d}/{total}] index={record.get('index')} "
            f"{elapsed:5.1f}s expanded_this_run={expanded_this_run} "
            f"server_cache_hits={service_cache_hits} eta={eta}s",
            flush=True,
        )

    write_json_atomic(output_file, output_records)
    print(f"Expanded claims written to {output_file}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
