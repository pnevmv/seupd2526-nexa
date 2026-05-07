#!/usr/bin/env python3
"""Compare retrieval similarity functions on EN/FR/DE dev sets.

Each similarity gets its own freshly-built index so that norm encoding
at index time matches the scoring model at search time.

Similarities tested:
  BM25   k1=1.2, b=0.75  (baseline)
  Classic                 (TF-IDF / vector space)
  LMD    mu ∈ {500, 1000, 2000, 4000}
  LMJM   lambda ∈ {0.1, 0.3, 0.5}

Usage:
    python3 test_similarity_dev.py
"""

import collections
import json
import re
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

# ---------------------------------------------------------------------------
# Similarity configurations to test
# ---------------------------------------------------------------------------

RUNS = [
    # BM25 baseline
    {"label": "BM25 k1=1.2 b=0.75", "similarity": "BM25",    "bm25_k1": 1.2,  "bm25_b": 0.75},
    # TF-IDF vector space
    {"label": "Classic (TF-IDF)",    "similarity": "CLASSIC"},
    # LM Dirichlet
    {"label": "LMD mu=500",          "similarity": "LMD",     "lmd_mu": 500},
    {"label": "LMD mu=1000",         "similarity": "LMD",     "lmd_mu": 1000},
    {"label": "LMD mu=2000",         "similarity": "LMD",     "lmd_mu": 2000},
    {"label": "LMD mu=4000",         "similarity": "LMD",     "lmd_mu": 4000},
    # LM Jelinek-Mercer
    {"label": "LMJM lambda=0.1",     "similarity": "LMJM",    "lmjm_lambda": 0.1},
    {"label": "LMJM lambda=0.3",     "similarity": "LMJM",    "lmjm_lambda": 0.3},
    {"label": "LMJM lambda=0.5",     "similarity": "LMJM",    "lmjm_lambda": 0.5},
]

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
bm25_k1: {bm25_k1}
bm25_b: {bm25_b}
similarity: {similarity}
lmd_mu: {lmd_mu}
lmjm_lambda: {lmjm_lambda}
"""

EN_CONFIG_BEST = """\
#File used to set up all the parameters for the project

language: English

tokenizerModel: code/java/src/main/resources/en/openNLPmodels/en-tokens.bin
sentenceModel: code/java/src/main/resources/en/openNLPmodels/en-sentence.bin
posModel: code/java/src/main/resources/en/openNLPmodels/en-pos.bin
lemmatizerModel: code/java/src/main/resources/en/openNLPmodels/en-lemmas.bin
synonymsFile: src/main/resources/en/synonyms/synonyms.txt
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
    missing = 0
    for qid in gold:
        r = best.get(qid)
        if r is None:
            missing += 1
        elif r < 5:
            rr5_sum += 1.0 / (r + 1)
    n = len(gold)
    return {"mrr@5": rr5_sum / n, "missing": missing, "n": n}


def rebuild_index():
    shutil.rmtree(INDEX_DIR, ignore_errors=True)
    t0 = time.time()
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector",
         "-cp", str(JAR), "it.unipd.dei.se.nexa.indexer.DirectoryIndexer"],
        cwd=REPO_ROOT, capture_output=True, text=True)
    elapsed = time.time() - t0
    if proc.returncode != 0:
        print("  [INDEX FAILED]")
        print(proc.stderr[-600:])
        return False
    last = [l for l in proc.stdout.splitlines() if l.strip()]
    if last:
        print(f"  {last[-1].strip()}  ({elapsed:.0f}s)")
    return True


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    if not JAR.exists():
        print(f"ERROR: JAR not found at {JAR}")
        print("Run: cd code && mvn package -q")
        sys.exit(1)

    RUNS_DIR.mkdir(exist_ok=True)
    backup_main = CONFIG_MAIN.read_text()
    backup_en   = CONFIG_EN.read_text()

    results = {}  # {label: {lang: metrics}}

    total = len(RUNS)
    try:
        for i, run in enumerate(RUNS, 1):
            label = run["label"]
            sim   = run["similarity"]
            k1    = run.get("bm25_k1", 1.2)
            b     = run.get("bm25_b",  0.75)
            mu    = run.get("lmd_mu",  2000)
            lam   = run.get("lmjm_lambda", 0.1)

            print(f"\n[{i:02d}/{total}] {label}")
            print(f"  Rebuilding index with similarity={sim} ...")

            # Write best-analyzer EN config
            CONFIG_EN.write_text(EN_CONFIG_BEST)

            # Write main config for indexing (topics/runID don't matter during index)
            index_cfg = MAIN_TEMPLATE.format(
                topics="datasets/en_dev.json", run_id="tmp-index",
                bm25_k1=k1, bm25_b=b, similarity=sim, lmd_mu=mu, lmjm_lambda=lam)
            CONFIG_MAIN.write_text(index_cfg)

            if not rebuild_index():
                print("  Skipping search (index build failed).")
                continue

            results[label] = {}
            for lang in LANG_TESTS:
                run_id = f"sim-{label.replace(' ', '_').replace('=', '')}-{lang}"
                # Write per-language search config
                search_cfg = MAIN_TEMPLATE.format(
                    topics=LANG_TESTS[lang]["topics"], run_id=run_id,
                    bm25_k1=k1, bm25_b=b, similarity=sim, lmd_mu=mu, lmjm_lambda=lam)
                CONFIG_MAIN.write_text(search_cfg)

                proc = subprocess.run(
                    ["java", "--add-modules", "jdk.incubator.vector",
                     "-cp", str(JAR), "it.unipd.dei.se.nexa.searcher.Searcher"],
                    cwd=REPO_ROOT, capture_output=True, text=True)
                run_file = RUNS_DIR / f"{run_id}.txt"
                if proc.returncode != 0 or not run_file.exists():
                    print(f"  [{lang.upper()} FAILED]")
                    print(proc.stderr[-400:])
                else:
                    m = evaluate(run_file, LANG_TESTS[lang]["claims"])
                    results[label][lang] = m
                    print(f"  {lang.upper()}: MRR@5={m['mrr@5']:.4f}  missing={m['missing']}/{m['n']}")

    finally:
        CONFIG_EN.write_text(backup_en)
        CONFIG_MAIN.write_text(backup_main)
        print("\nConfigs restored.")

    # ---------------------------------------------------------------------------
    # Results table
    # ---------------------------------------------------------------------------
    W = 22
    hdr = (f"{'Configuration':<{W}} | {'EN MRR@5':>8} | {'FR MRR@5':>8} | "
           f"{'DE MRR@5':>8} | {'Avg MRR@5':>9}")
    sep = "-" * (W + 1) + "+" + "-" * 10 + "+" + "-" * 10 + "+" + "-" * 10 + "+" + "-" * 11
    print(f"\n{hdr}\n{sep}")

    baseline_avg = 0.0
    for run in RUNS:
        label = run["label"]
        r  = results.get(label, {})
        e5 = r.get("en", {}).get("mrr@5", float("nan"))
        f5 = r.get("fr", {}).get("mrr@5", float("nan"))
        d5 = r.get("de", {}).get("mrr@5", float("nan"))
        vals = [v for v in (e5, f5, d5) if v == v]  # drop NaN
        avg  = sum(vals) / len(vals) if vals else float("nan")
        if label == RUNS[0]["label"]:
            baseline_avg = avg
        marker = " *" if avg > baseline_avg else ""
        print(f"{label:<{W}} | {e5:>8.4f} | {f5:>8.4f} | {d5:>8.4f} | {avg:>9.4f}{marker}")
    print(sep)


if __name__ == "__main__":
    main()
