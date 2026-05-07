#!/usr/bin/env python3
"""Generate BM25 lexical run files for FR and DE test sets.

Patches config.yml for each language, runs the Java Searcher, then restores
the original config. Produces:
  runs/nexa-fr-test-baseline.txt  (1220 FR test queries, translated to EN)
  runs/nexa-de-test-baseline.txt  (876 DE test queries, translated to EN)

Usage:
    python3 generate_test_runs.py
"""

import subprocess
import time
from pathlib import Path

REPO_ROOT  = Path(__file__).resolve().parents[3]
CONFIG     = REPO_ROOT / "code/src/main/config/config.yml"
JAR        = REPO_ROOT / "code/target/nexa-0.1-jar-with-dependencies.jar"
MAIN_CLASS = "it.unipd.dei.se.nexa.searcher.Searcher"
RUNS_DIR   = REPO_ROOT / "runs"

MAX_DOCS = 1000

LANGS = {
    "en": {
        "topics": "datasets/final_en_test.json",
        "run_id": "nexa-en-test-baseline",
    },
    "fr": {
        "topics": "datasets/processed/queries/translated/fr_test_en_translated.json",
        "run_id": "nexa-fr-test-baseline",
    },
    "de": {
        "topics": "datasets/processed/queries/translated/de_test_en_translated.json",
        "run_id": "nexa-de-test-baseline",
    },
}


def run_searcher(topics: str, run_id: str) -> bool:
    original = CONFIG.read_text()
    patched  = original
    # Patch topics and runID lines in-place
    lines = []
    for line in original.splitlines():
        if line.startswith("topics:"):
            lines.append(f"topics: {topics}")
        elif line.startswith("runID:"):
            lines.append(f"runID: {run_id}")
        elif line.startswith("reRank:"):
            lines.append("reRank: false")
        elif line.startswith("searchMode:"):
            lines.append("searchMode: lexical")   # no embedding server needed
        elif line.startswith("enableQueryExpansion:"):
            lines.append("enableQueryExpansion: false")
        elif line.startswith("translateNonEnglishClaimsToEnglish:"):
            lines.append("translateNonEnglishClaimsToEnglish: false")
        elif line.startswith("translateNonEnglishPublicationsToEnglish:"):
            lines.append("translateNonEnglishPublicationsToEnglish: false")
        elif line.startswith("maxDocsRetrieved:"):
            lines.append(f"maxDocsRetrieved: {MAX_DOCS}")
        else:
            lines.append(line)
    CONFIG.write_text("\n".join(lines) + "\n")

    try:
        t0   = time.time()
        proc = subprocess.run(
            ["java", "--add-modules", "jdk.incubator.vector",
             "-cp", str(JAR), MAIN_CLASS],
            cwd=REPO_ROOT, capture_output=True, text=True,
        )
        elapsed = time.time() - t0
        run_file = RUNS_DIR / f"{run_id}.txt"

        if proc.returncode != 0:
            print(f"  FAILED (exit {proc.returncode})")
            print("STDOUT:", proc.stdout[:2000])
            print("STDERR:", proc.stderr[:2000])
            return False

        if not run_file.exists():
            print(f"  FAILED — run file not created")
            print(proc.stdout[-500:])
            return False

        lines_out = run_file.read_text().splitlines()
        qids = {int(l.split()[0]) for l in lines_out if l.strip()}
        print(f"  OK — {len(lines_out)} lines, {len(qids)} queries  [{elapsed:.0f}s]")
        print(f"  → {run_file.name}")
        return True
    finally:
        CONFIG.write_text(original)


def main():
    if not JAR.exists():
        print("ERROR: JAR not found. Run: cd code && mvn package -DskipTests")
        return

    RUNS_DIR.mkdir(exist_ok=True)

    for lang, cfg in LANGS.items():
        print(f"\n=== {lang.upper()} test ===")
        run_searcher(cfg["topics"], cfg["run_id"])

    print("\nDone.")


if __name__ == "__main__":
    main()
