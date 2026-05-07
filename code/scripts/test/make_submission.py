#!/usr/bin/env python3
"""Convert run files to CodaBench submission format.

Reads one TREC-format run file per language, extracts the top-5 pubkeys per
claim, writes predictions_{lang}.tsv, and zips them into predictions.zip.

Usage:
    python3 make_submission.py --en runs/nexa-en-final.txt \
                               --de runs/nexa-de-final.txt \
                               --fr runs/nexa-fr-final.txt

Any language flag can be omitted if you are not submitting for that language.
"""

import argparse
import collections
import json
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
OUT_DIR = REPO_ROOT / "submission"
ZIP_PATH = REPO_ROOT / "predictions.zip"
TOP_K = 5


def parse_run(run_file: Path) -> dict[int, list[int]]:
    """Return {qid: [pubkey_rank0, pubkey_rank1, ...up to TOP_K]} from a TREC run file."""
    by_qid: dict[int, list[tuple[int, int]]] = collections.defaultdict(list)
    with run_file.open() as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4:
                continue
            try:
                qid, pubkey, rank = int(parts[0]), int(parts[2]), int(parts[3])
            except ValueError:
                continue
            by_qid[qid].append((rank, pubkey))

    result = {}
    for qid, entries in by_qid.items():
        entries.sort()
        result[qid] = [pk for _, pk in entries[:TOP_K]]
    return result


def write_tsv(lang: str, run_file: Path, claims_file: Path) -> Path:
    claims = json.loads(claims_file.read_text())
    preds = parse_run(run_file)

    OUT_DIR.mkdir(exist_ok=True)
    tsv_path = OUT_DIR / f"predictions_{lang}.tsv"
    with tsv_path.open("w") as f:
        f.write("index\tpreds\n")
        for claim in claims:
            idx = claim["index"]
            top5 = preds.get(idx, [])
            # pad with -1 if fewer than TOP_K results
            while len(top5) < TOP_K:
                top5.append(-1)
            f.write(f"{idx}\t{top5}\n")

    print(f"  [{lang}] {tsv_path.name}  ({len(claims)} claims)")
    return tsv_path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--en", type=Path, help="Run file for English")
    parser.add_argument("--de", type=Path, help="Run file for German")
    parser.add_argument("--fr", type=Path, help="Run file for French")
    parser.add_argument("--en-claims", type=Path, default=REPO_ROOT / "datasets/final_en_test.json",
                        help="Claims file for English (default: final_en_test.json)")
    parser.add_argument("--de-claims", type=Path, default=REPO_ROOT / "datasets/final_de_test.json",
                        help="Claims file for German (default: final_de_test.json)")
    parser.add_argument("--fr-claims", type=Path, default=REPO_ROOT / "datasets/final_fr_test.json",
                        help="Claims file for French (default: final_fr_test.json)")
    args = parser.parse_args()

    lang_map = {
        "en": (args.en, args.en_claims),
        "de": (args.de, args.de_claims),
        "fr": (args.fr, args.fr_claims),
    }

    tsv_files = []
    for lang, (run_file, claims_file) in lang_map.items():
        if run_file is None:
            continue
        if not run_file.exists():
            print(f"  [{lang}] ERROR: run file not found: {run_file}")
            continue
        tsv_files.append(write_tsv(lang, run_file, claims_file))

    if not tsv_files:
        print("No languages provided. Pass at least one of --en, --de, --fr.")
        return

    with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED) as zf:
        for tsv in tsv_files:
            zf.write(tsv, tsv.name)

    print(f"\nCreated {ZIP_PATH}  ({ZIP_PATH.stat().st_size // 1024} KB)")
    print("Contents:", [f.filename for f in zipfile.ZipFile(ZIP_PATH).infolist()])


if __name__ == "__main__":
    main()
