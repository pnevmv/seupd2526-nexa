#!/usr/bin/env python3
"""Test Pseudo-Relevance Feedback (PRF) on top of best lexical config:
   Standard + LowerCase + Length + StopList + KStem

The index is assumed to already exist (built by a previous run).
Only search configs change — no index rebuilds.

PRF grid:
  docs ∈ {3, 5, 10}  ×  terms ∈ {3, 5, 10}  +  baseline (no PRF)

Usage:
    python3 test_prf_dev.py
    python3 test_prf_dev.py --rebuild   # force index rebuild first
"""

import argparse
import collections
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT   = Path(__file__).resolve().parent
CONFIG_MAIN = REPO_ROOT / "code/src/main/config/config.yml"
CONFIG_EN   = REPO_ROOT / "code/src/main/config/config_en.yml"
JAR         = REPO_ROOT / "code/target/nexa-0.1-jar-with-dependencies.jar"
MAIN_CLASS  = "it.unipd.dei.se.nexa.searcher.Searcher"
RUNS_DIR    = REPO_ROOT / "runs"
INDEX_DIR   = REPO_ROOT / "experiment/index"

EN_STOP_PATH = "src/main/resources/en/stoplists/stopListEN_FULL.txt"

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
# PRF run matrix
# ---------------------------------------------------------------------------

RUNS = [{"label": "baseline (no PRF)", "prf": False, "docs": 0, "terms": 0}]
for docs in [3, 5, 10]:
    for terms in [3, 5, 10]:
        RUNS.append({
            "label": f"prf-d{docs}-t{terms}",
            "prf":   True,
            "docs":  docs,
            "terms": terms,
        })

# ---------------------------------------------------------------------------
# Config templates
# ---------------------------------------------------------------------------

SEARCH_TEMPLATE = """\
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
bm25_k1: 1.2
bm25_b: 0.75
{prf_block}"""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def patch_yaml(path, patches):
    content = path.read_text()
    original = content
    for key, value in patches.items():
        content, n = re.subn(rf'^({re.escape(key)}:)[ \t]*.*$', rf'\1 {value}',
                             content, flags=re.MULTILINE)
        if n == 0:
            print(f"  [WARN] '{key}' not found")
    path.write_text(content)
    return original

def evaluate(run_file, claims_file):
    gold = {c["index"]: c["pubkey"] for c in json.loads(claims_file.read_text())}
    best = {}
    with run_file.open() as f:
        for line in f:
            parts = line.split()
            if len(parts) < 4: continue
            qid, pk, rank = int(parts[0]), int(parts[2]), int(parts[3])
            if gold.get(qid) == pk and qid not in best:
                best[qid] = rank
    buckets = collections.Counter()
    rr_sum = rr5_sum = 0.0
    for qid in gold:
        r = best.get(qid)
        if r is None:
            buckets["missing"] += 1
        else:
            rr_sum += 1.0 / (r + 1)
            if r < 5: rr5_sum += 1.0 / (r + 1)
            if r == 0:    buckets["hit@1"] += 1
            elif r < 5:   buckets["hit@5"] += 1
            elif r < 10:  buckets["hit@10"] += 1
            elif r < 100: buckets["hit@100"] += 1
    n = len(gold)
    return {"mrr": rr_sum/n, "mrr@5": rr5_sum/n,
            "hit@1": buckets["hit@1"], "hit@5": buckets["hit@5"],
            "hit@10": buckets["hit@10"], "missing": buckets["missing"], "n": n}

def rebuild_index():
    shutil.rmtree(INDEX_DIR, ignore_errors=True)
    t0 = time.time()
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector", "-cp", str(JAR),
         "it.unipd.dei.se.nexa.indexer.DirectoryIndexer"],
        cwd=REPO_ROOT, capture_output=True, text=True)
    elapsed = time.time() - t0
    if proc.returncode != 0:
        print("  [INDEX FAILED]"); print(proc.stderr[-500:]); return -1.0
    last = [l for l in proc.stdout.splitlines() if l.strip()]
    if last: print(f"  {last[-1].strip()}  ({elapsed:.0f}s)")
    return elapsed

def run_search(lang, run_id, prf_block):
    lt = LANG_TESTS[lang]
    CONFIG_MAIN.write_text(SEARCH_TEMPLATE.format(
        topics=lt["topics"], run_id=run_id, prf_block=prf_block))
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector", "-cp", str(JAR), MAIN_CLASS],
        cwd=REPO_ROOT, capture_output=True, text=True)
    run_file = RUNS_DIR / f"{run_id}.txt"
    if proc.returncode != 0 or not run_file.exists():
        print(f"  [{lang.upper()} FAILED]"); print(proc.stderr[-300:]); return None
    return evaluate(run_file, lt["claims"])

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rebuild", action="store_true",
                        help="Rebuild the index before running (uses best config)")
    args = parser.parse_args()

    if not JAR.exists():
        print("ERROR: JAR not found."); sys.exit(1)

    RUNS_DIR.mkdir(exist_ok=True)
    backup_main = CONFIG_MAIN.read_text()
    backup_en   = CONFIG_EN.read_text()

    langs = list(LANG_TESTS.keys())
    results = {}   # {label: {lang: metrics}}

    try:
        if args.rebuild or not INDEX_DIR.exists():
            print("Building index with best config (Standard+Stop+KStem)...")
            patch_yaml(CONFIG_EN, {
                "tokenizerType":           "Standard",
                "stemFilter":              "KStem",
                "customStopList":          EN_STOP_PATH,
                "synonymsFile":            "",
                "nGramsFilter":            "false",
                "positionFilter":          "false",
                "expansionFilter":         "false",
                "repeatedLetterFilter":    "false",
                "posOpnNLPFilter":         "false",
                "englishPossessiveFilter": "true",
            })
            if rebuild_index() < 0:
                return
        else:
            print(f"Reusing existing index at {INDEX_DIR}\n")

        total = len(RUNS)
        for i, run in enumerate(RUNS, 1):
            label = run["label"]
            results[label] = {}

            if run["prf"]:
                prf_block = (f"prf: true\n"
                             f"numOfDocsToRetrieveForPrf: {run['docs']}\n"
                             f"numOfTokenFromPrf: {run['terms']}")
                desc = f"docs={run['docs']} terms={run['terms']}"
            else:
                prf_block = ""
                desc = "no PRF"

            print(f"[{i:02d}/{total}] {label:<20}  ({desc})")

            for lang in langs:
                run_id = f"prf-{label.replace(' ', '_').replace('(', '').replace(')', '')}-{lang}"
                m = run_search(lang, run_id, prf_block)
                if m:
                    results[label][lang] = m
                    print(f"  {lang.upper()}: MRR@5={m['mrr@5']:.4f}  MRR={m['mrr']:.4f}"
                          f"  hit@1={m['hit@1']}  miss={m['missing']}")
            print()

    finally:
        CONFIG_EN.write_text(backup_en)
        CONFIG_MAIN.write_text(backup_main)
        print("Configs restored.")

    # ---------------------------------------------------------------------------
    # Results table
    # ---------------------------------------------------------------------------
    W = 22
    hdr = (f"{'Configuration':<{W}} | {'EN MRR@5':>8} {'EN MRR':>7} |"
           f" {'FR MRR@5':>8} {'FR MRR':>7} | {'DE MRR@5':>8} {'DE MRR':>7} | {'Avg MRR@5':>9}")
    sep = "-"*(W+1) + "+" + "-"*18 + "+" + "-"*18 + "+" + "-"*18 + "+" + "-"*11
    print(f"\n{hdr}\n{sep}")
    for run in RUNS:
        label = run["label"]
        r  = results.get(label, {})
        en = r.get("en", {}); fr = r.get("fr", {}); de = r.get("de", {})
        e5, e = en.get("mrr@5", 0), en.get("mrr", 0)
        f5, f = fr.get("mrr@5", 0), fr.get("mrr", 0)
        d5, d = de.get("mrr@5", 0), de.get("mrr", 0)
        avg   = (e5 + f5 + d5) / 3
        marker = " *" if run["prf"] and avg > results.get("baseline (no PRF)", {}).get("en", {}).get("mrr@5", 0) else ""
        print(f"{label:<{W}} | {e5:>8.4f} {e:>7.4f} | {f5:>8.4f} {f:>7.4f} | {d5:>8.4f} {d:>7.4f} | {avg:>9.4f}{marker}")
    print(sep)

    # Best per language
    print()
    for lang in langs:
        best = max(
            ((run["label"], results[run["label"]][lang]["mrr@5"])
             for run in RUNS if lang in results.get(run["label"], {})),
            key=lambda x: x[1], default=(None, 0)
        )
        print(f"  {lang.upper()} best: {best[0]}  MRR@5={best[1]:.4f}")


if __name__ == "__main__":
    main()
