#!/usr/bin/env python3
"""Fielded search experiment: title/abstract boosts, venue/authors, query mode, query pruning.

No index rebuild needed — all variations are query-time only.

Parts:
  A. Title boost sweep      (abstractBoost=1.0 fixed)
  B. Abstract boost sweep   (best titleBoost from A)
  C. Venue + authors boost  (best from B)
  D. Query mode             (should / mixed-N / must)
  E. IDF-based pruning      (topKQueryTerms: 0=all, 10, 15, 20, 30)

Usage:
    python3 test_fielded_dev.py
    python3 test_fielded_dev.py --rebuild   # rebuild index first
"""

import argparse
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
    "en": {"topics": "datasets/en_dev.json",
           "claims": REPO_ROOT / "datasets/en_dev.json"},
    "fr": {"topics": "datasets/processed/queries/translated/fr_dev_en_translated.json",
           "claims": REPO_ROOT / "datasets/fr_dev.json"},
    "de": {"topics": "datasets/processed/queries/translated/de_dev_en_translated.json",
           "claims": REPO_ROOT / "datasets/de_dev.json"},
}

# ---------------------------------------------------------------------------
# Run matrix
# ---------------------------------------------------------------------------

def make_cfg(label, **kwargs):
    defaults = dict(titleBoost=2.0, abstractBoost=1.0, venueBoost=0.0, authorsBoost=0.0,
                    queryMode="should", mustTermCount=3, topKQueryTerms=0,
                    phraseBoost=0.0, fuzzyBoost=0.0)
    defaults.update(kwargs)
    defaults["label"] = label
    return defaults

# Part A: title boost (abstractBoost=1.0 fixed)
PART_A = [make_cfg(f"A: title^{t} abs^1", titleBoost=t, abstractBoost=1.0)
          for t in [1.0, 1.5, 2.0, 3.0, 4.0, 5.0]]

# Part B: abstract boost (use best titleBoost from A = to be filled dynamically, default 2.0)
PART_B = [make_cfg(f"B: title^2 abs^{a}", titleBoost=2.0, abstractBoost=a)
          for a in [0.5, 1.0, 1.5, 2.0, 3.0]]

# Part C: venue + authors (best title/abstract from above, we'll use title=2, abs=1 as base)
PART_C = []
for v in [0.0, 0.1, 0.3, 0.5]:
    for auth in [0.0, 0.1, 0.3]:
        if v == 0.0 and auth == 0.0:
            continue  # already in Part A/B as baseline
        PART_C.append(make_cfg(f"C: v^{v} a^{auth}", titleBoost=2.0, abstractBoost=1.0,
                               venueBoost=v, authorsBoost=auth))

# Part D: query mode
PART_D = [make_cfg("D: mode=should",   titleBoost=2.0, abstractBoost=1.0, queryMode="should")]
for n in [2, 3, 5, 8]:
    PART_D.append(make_cfg(f"D: mixed must={n}", titleBoost=2.0, abstractBoost=1.0,
                           queryMode="mixed", mustTermCount=n))
PART_D.append(make_cfg("D: mode=must", titleBoost=2.0, abstractBoost=1.0, queryMode="must"))

# Part E: IDF-based pruning
PART_E = [make_cfg(f"E: topK={k}", titleBoost=2.0, abstractBoost=1.0, topKQueryTerms=k)
          for k in [0, 10, 15, 20, 30]]

ALL_RUNS = PART_A + PART_B + PART_C + PART_D + PART_E

# ---------------------------------------------------------------------------
# Templates
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
bm25_k1: 1.0
bm25_b: 0.75
similarity: BM25
lmd_mu: 2000
lmjm_lambda: 0.1
titleBoost: {titleBoost}
abstractBoost: {abstractBoost}
venueBoost: {venueBoost}
authorsBoost: {authorsBoost}
queryMode: {queryMode}
mustTermCount: {mustTermCount}
topKQueryTerms: {topKQueryTerms}
phraseBoost: {phraseBoost}
fuzzyBoost: {fuzzyBoost}
"""

EN_CONFIG_BEST = """\
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
    rr5 = sum(1.0 / (r + 1) for r in best.values() if r < 5)
    return rr5 / len(gold)


def rebuild_index():
    shutil.rmtree(INDEX_DIR, ignore_errors=True)
    CONFIG_MAIN.write_text(MAIN_TEMPLATE.format(
        topics="datasets/en_dev.json", run_id="tmp-index",
        titleBoost=2.0, abstractBoost=1.0, venueBoost=0.0, authorsBoost=0.0,
        queryMode="should", mustTermCount=3, topKQueryTerms=0,
        phraseBoost=0.0, fuzzyBoost=0.0))
    t0 = time.time()
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector", "-cp", str(JAR),
         "it.unipd.dei.se.nexa.indexer.DirectoryIndexer"],
        cwd=REPO_ROOT, capture_output=True, text=True)
    elapsed = time.time() - t0
    if proc.returncode != 0:
        print("  [INDEX FAILED]"); print(proc.stderr[-600:]); return False
    last = [l for l in proc.stdout.splitlines() if l.strip()]
    if last: print(f"  {last[-1].strip()}  ({elapsed:.0f}s)")
    return True


def run_search(lang, run_id, cfg):
    CONFIG_MAIN.write_text(MAIN_TEMPLATE.format(
        topics=LANG_TESTS[lang]["topics"], run_id=run_id, **cfg))
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector", "-cp", str(JAR),
         "it.unipd.dei.se.nexa.searcher.Searcher"],
        cwd=REPO_ROOT, capture_output=True, text=True)
    run_file = RUNS_DIR / f"{run_id}.txt"
    if proc.returncode != 0 or not run_file.exists():
        print(f"  [{lang.upper()} FAILED]"); print(proc.stderr[-400:]); return None
    return evaluate(run_file, LANG_TESTS[lang]["claims"])


def print_section(title, runs, results, baseline_avg):
    W = 28
    print(f"\n{'='*60}")
    print(f"  {title}")
    print(f"{'='*60}")
    hdr = f"{'Config':<{W}} | {'EN':>7} | {'FR':>7} | {'DE':>7} | {'Avg':>7}"
    sep = "-" * (W + 1) + "+" + "-" * 9 + "+" + "-" * 9 + "+" + "-" * 9 + "+" + "-" * 8
    print(f"{hdr}\n{sep}")
    best_avg_local = 0.0
    for cfg in runs:
        label = cfg["label"]
        r = results.get(label, {})
        en = r.get("en", float("nan"))
        fr = r.get("fr", float("nan"))
        de = r.get("de", float("nan"))
        vals = [v for v in (en, fr, de) if v == v]
        avg = sum(vals) / len(vals) if vals else float("nan")
        if avg > best_avg_local:
            best_avg_local = avg
        marker = " *" if avg > baseline_avg + 0.0001 else ""
        print(f"{label:<{W}} | {en:>7.4f} | {fr:>7.4f} | {de:>7.4f} | {avg:>7.4f}{marker}")
    print(sep)
    return best_avg_local

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rebuild", action="store_true")
    args = parser.parse_args()

    if not JAR.exists():
        print(f"ERROR: JAR not found at {JAR}")
        sys.exit(1)

    RUNS_DIR.mkdir(exist_ok=True)
    backup_main = CONFIG_MAIN.read_text()
    backup_en   = CONFIG_EN.read_text()
    results = {}

    total = len(ALL_RUNS)
    try:
        CONFIG_EN.write_text(EN_CONFIG_BEST)

        if args.rebuild or not INDEX_DIR.exists():
            print("Rebuilding index (BM25 k1=1.0 b=0.75, best analyzer) ...")
            if not rebuild_index():
                return
        else:
            print(f"Reusing index at {INDEX_DIR}\n")

        print(f"Running {total} configurations × 3 languages = {total*3} searches\n")

        for i, cfg in enumerate(ALL_RUNS, 1):
            label = cfg["label"]
            results[label] = {}
            avgs = []
            for lang in LANG_TESTS:
                tag = label.replace(" ", "_").replace("^", "x").replace("=", "")
                run_id = f"fd-{tag}-{lang}"[:80]
                m = run_search(lang, run_id, cfg)
                if m is not None:
                    results[label][lang] = m
                    avgs.append(m)
            avg = sum(avgs) / len(avgs) if avgs else 0.0
            en = results[label].get("en", 0)
            fr = results[label].get("fr", 0)
            de = results[label].get("de", 0)
            print(f"[{i:03d}/{total}] {label:<30}  EN={en:.4f} FR={fr:.4f} DE={de:.4f} Avg={avg:.4f}")

    finally:
        CONFIG_EN.write_text(backup_en)
        CONFIG_MAIN.write_text(backup_main)
        print("\nConfigs restored.")

    # Baseline = title^2 abs^1
    baseline_label = "A: title^2.0 abs^1"
    bl = results.get(baseline_label, {})
    baseline_avg = sum(bl.values()) / len(bl) if bl else 0.0

    print(f"\nBaseline (title^2 abs^1): EN={bl.get('en',0):.4f} "
          f"FR={bl.get('fr',0):.4f} DE={bl.get('de',0):.4f} "
          f"Avg={baseline_avg:.4f}")

    print_section("Part A: Title boost sweep (abs=1.0 fixed)", PART_A, results, baseline_avg)
    print_section("Part B: Abstract boost sweep (title=2.0 fixed)", PART_B, results, baseline_avg)
    print_section("Part C: Venue + Authors boost", PART_C, results, baseline_avg)
    print_section("Part D: Query mode (should / mixed / must)", PART_D, results, baseline_avg)
    print_section("Part E: IDF-based query pruning (topK terms)", PART_E, results, baseline_avg)

    # Global best
    best_label, best_avg = max(
        ((label, sum(v.values()) / len(v)) for label, v in results.items() if v),
        key=lambda x: x[1])
    print(f"\n{'='*60}")
    print(f"  GLOBAL BEST: {best_label}  Avg MRR@5={best_avg:.4f}  "
          f"(+{best_avg - baseline_avg:+.4f} vs baseline)")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
