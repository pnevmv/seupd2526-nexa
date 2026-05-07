#!/usr/bin/env python3
"""BM25 k1 × b grid search on EN/FR/DE dev sets.

Rebuilds the index once (BM25 with default k1/b), then sweeps all
k1 × b combinations at query time only — no repeated indexing needed
because Lucene stores raw field-length norms and applies k1/b at scoring.

Grid:
  k1 ∈ {0.5, 0.8, 1.0, 1.2, 1.5, 2.0, 3.0}
   b ∈ {0.0, 0.25, 0.5, 0.75, 1.0}

Usage:
    python3 test_bm25_grid_dev.py
    python3 test_bm25_grid_dev.py --rebuild   # force index rebuild
"""

import argparse
import collections
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT   = Path(__file__).resolve().parents[3]
CONFIG_MAIN = REPO_ROOT / "code/src/main/config/config.yml"
CONFIG_EN   = REPO_ROOT / "code/src/main/config/config_en.yml"
JAR         = REPO_ROOT / "code/target/nexa-0.1-jar-with-dependencies.jar"
RUNS_DIR    = REPO_ROOT / "runs"
INDEX_DIR   = REPO_ROOT / "experiment/index"

LANG_TESTS = {
    "en": {
        "topics": "datasets/en_dev.json",
        "claims": REPO_ROOT / "datasets/en_dev.json",
    },
    "fr": {
        "topics": "datasets/processed/queries/translated/fr_dev_en_translated.json",
        "claims": REPO_ROOT / "datasets/fr_dev.json",
    },
    "de": {
        "topics": "datasets/processed/queries/translated/de_dev_en_translated.json",
        "claims": REPO_ROOT / "datasets/de_dev.json",
    },
}

K1_VALUES = [0.5, 0.8, 1.0, 1.2, 1.5, 2.0, 3.0]
B_VALUES  = [0.0, 0.25, 0.5, 0.75, 1.0]

# ---------------------------------------------------------------------------
# Config templates
# ---------------------------------------------------------------------------

MAIN_TEMPLATE = """\
collectionPath: datasets/processed/documents/translated/collection_data_en_translated.json
indexPath: "experiment/index"
languageDetectorModel: src/main/resources/langdetect-183.bin
embeddingsEnabled: false
embeddingsServiceUrl: http://localhost:8080/process
translateNonEnglishPublicationsToEnglish: false
translateNonEnglishClaimsToEnglish: false
translationProvider: gemma
translationTargetLanguage: en
translationCommand:
translationServiceUrl: http://127.0.0.1:8081/translate
enableQueryExpansion: false
queryExpansionServiceUrl: http://127.0.0.1:8001/expand
searchMode: lexical
topics: {topics}
runID: {run_id}
runPath: runs
maxDocsRetrieved: 50
reRank: false
reRankServiceUrl: http://127.0.0.1:8082/rerank
numOfDocsToRerank: 50
rerankAlpha: 0.6
rrfAlpha: 0.2
rrfK: 60
bm25_k1: {k1}
bm25_b: {b}
similarity: BM25
lmd_mu: 2000
lmjm_lambda: 0.1
"""

EN_CONFIG_BEST = """\
#File used to set up all the parameters for the project

language: English

tokenizerModel: code/java/src/main/resources/en/openNLPmodels/en-tokens.bin
sentenceModel: code/java/src/main/resources/en/openNLPmodels/en-sentence.bin
posModel: code/java/src/main/resources/en/openNLPmodels/en-pos.bin
lemmatizerModel: code/java/src/main/resources/en/openNLPmodels/en-lemmas.bin
synonymsFile:
languageDetectorModel: src/main/resources/langdetect-183.bin

minLength: 2
maxLength: 36
nGramsFilter: false
shingleSize: 3
positionFilter: false
positionIncrement: 1
expansionFilter: false
posOpnNLPFilter: false
repeatedLetterFilter: false
englishPossessiveFilter: true
tokenizerType: Standard
customStopList: src/main/resources/en/stoplists/stopListEN_FULL.txt
stemFilter: KStem
"""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def evaluate(run_file, claims_file):
    gold = {c["index"]: c["pubkey"] for c in json.loads(claims_file.read_text())}
    best = {}
    with run_file.open() as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4:
                continue
            qid, pk, rank = int(parts[0]), int(parts[2]), int(parts[3])
            if gold.get(qid) == pk and qid not in best:
                best[qid] = rank
    rr5_sum = 0.0
    for qid in gold:
        r = best.get(qid)
        if r is not None and r < 5:
            rr5_sum += 1.0 / (r + 1)
    return rr5_sum / len(gold)


def rebuild_index(k1=1.2, b=0.75):
    shutil.rmtree(INDEX_DIR, ignore_errors=True)
    CONFIG_MAIN.write_text(MAIN_TEMPLATE.format(
        topics="datasets/en_dev.json", run_id="tmp-index", k1=k1, b=b))
    t0 = time.time()
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector",
         "-cp", str(JAR), "it.unipd.dei.se.nexa.indexer.DirectoryIndexer"],
        cwd=REPO_ROOT, capture_output=True, text=True)
    elapsed = time.time() - t0
    if proc.returncode != 0:
        print("  [INDEX FAILED]"); print(proc.stderr[-600:]); return False
    last = [l for l in proc.stdout.splitlines() if l.strip()]
    if last:
        print(f"  {last[-1].strip()}  ({elapsed:.0f}s)")
    return True


def run_search(lang, run_id, k1, b):
    CONFIG_MAIN.write_text(MAIN_TEMPLATE.format(
        topics=LANG_TESTS[lang]["topics"], run_id=run_id, k1=k1, b=b))
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector",
         "-cp", str(JAR), "it.unipd.dei.se.nexa.searcher.Searcher"],
        cwd=REPO_ROOT, capture_output=True, text=True)
    run_file = RUNS_DIR / f"{run_id}.txt"
    if proc.returncode != 0 or not run_file.exists():
        print(f"  [{lang.upper()} FAILED]"); print(proc.stderr[-300:]); return None
    return evaluate(run_file, LANG_TESTS[lang]["claims"])


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rebuild", action="store_true",
                        help="Force index rebuild (default: reuse existing)")
    args = parser.parse_args()

    if not JAR.exists():
        print(f"ERROR: JAR not found at {JAR}")
        sys.exit(1)

    RUNS_DIR.mkdir(exist_ok=True)
    backup_main = CONFIG_MAIN.read_text()
    backup_en   = CONFIG_EN.read_text()

    # results[k1][b] = {lang: mrr@5}
    results = {k1: {b: {} for b in B_VALUES} for k1 in K1_VALUES}
    total = len(K1_VALUES) * len(B_VALUES)

    try:
        CONFIG_EN.write_text(EN_CONFIG_BEST)

        if args.rebuild or not INDEX_DIR.exists():
            print("Building index (BM25, best analyzer config) ...")
            if not rebuild_index():
                return
        else:
            print(f"Reusing existing index at {INDEX_DIR}")

        print(f"\nSweeping {len(K1_VALUES)} k1 × {len(B_VALUES)} b = {total} combinations "
              f"× 3 languages = {total*3} searches\n")

        done = 0
        for k1 in K1_VALUES:
            for b in B_VALUES:
                done += 1
                avgs = []
                for lang in LANG_TESTS:
                    tag = f"bm25-k{k1}-b{b}".replace(".", "p")
                    run_id = f"{tag}-{lang}"
                    mrr5 = run_search(lang, run_id, k1, b)
                    if mrr5 is not None:
                        results[k1][b][lang] = mrr5
                        avgs.append(mrr5)
                avg = sum(avgs) / len(avgs) if avgs else 0.0
                en = results[k1][b].get("en", 0)
                fr = results[k1][b].get("fr", 0)
                de = results[k1][b].get("de", 0)
                print(f"[{done:02d}/{total}] k1={k1:<4} b={b:<4}  "
                      f"EN={en:.4f}  FR={fr:.4f}  DE={de:.4f}  Avg={avg:.4f}")

    finally:
        CONFIG_EN.write_text(backup_en)
        CONFIG_MAIN.write_text(backup_main)
        print("\nConfigs restored.")

    # ---------------------------------------------------------------------------
    # 2-D grid table (Avg MRR@5)
    # ---------------------------------------------------------------------------
    print("\n=== Avg MRR@5 grid (rows=k1, cols=b) ===\n")
    col_w = 8
    header = f"{'k1 \\ b':<8}" + "".join(f"{b:>{col_w}.2f}" for b in B_VALUES)
    print(header)
    print("-" * len(header))

    best_avg, best_k1, best_b = 0.0, None, None
    for k1 in K1_VALUES:
        row = f"{k1:<8.1f}"
        for b in B_VALUES:
            r = results[k1][b]
            vals = [v for v in r.values()]
            avg = sum(vals) / len(vals) if vals else 0.0
            if avg > best_avg:
                best_avg, best_k1, best_b = avg, k1, b
            marker = "*" if avg == best_avg else " "
            row += f"{avg:>{col_w-1}.4f}{marker}"
        print(row)

    print(f"\nBest: k1={best_k1}, b={best_b}  →  Avg MRR@5={best_avg:.4f}")

    # Per-language best
    print()
    for lang in LANG_TESTS:
        best_l, bk1, bb = 0.0, None, None
        for k1 in K1_VALUES:
            for b in B_VALUES:
                v = results[k1][b].get(lang, 0)
                if v > best_l:
                    best_l, bk1, bb = v, k1, b
        print(f"  {lang.upper()} best: k1={bk1}, b={bb}  MRR@5={best_l:.4f}")

    # Full table for copy-paste
    print("\n=== Full table (EN / FR / DE MRR@5) ===\n")
    W = 18
    print(f"{'Config':<{W}} | {'EN MRR@5':>8} | {'FR MRR@5':>8} | {'DE MRR@5':>8} | {'Avg':>8}")
    sep = "-" * (W + 1) + "+" + "-" * 10 + "+" + "-" * 10 + "+" + "-" * 10 + "+" + "-" * 9
    print(sep)
    for k1 in K1_VALUES:
        for b in B_VALUES:
            r  = results[k1][b]
            en = r.get("en", float("nan"))
            fr = r.get("fr", float("nan"))
            de = r.get("de", float("nan"))
            vals = [v for v in (en, fr, de) if v == v]
            avg  = sum(vals) / len(vals) if vals else float("nan")
            marker = " *" if (k1 == best_k1 and b == best_b) else ""
            print(f"k1={k1:<4} b={b:<4}      | {en:>8.4f} | {fr:>8.4f} | {de:>8.4f} | {avg:>8.4f}{marker}")
    print(sep)


if __name__ == "__main__":
    main()
