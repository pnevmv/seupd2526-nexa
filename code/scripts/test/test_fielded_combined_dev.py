#!/usr/bin/env python3
"""Combine best settings from fielded search experiment and fine-tune.

Best individual findings:
  - titleBoost=2.0, abstractBoost=3.0  (ratio 2:3 favors abstract)
  - authorsBoost=0.3 helps consistently
  - venueBoost has marginal effect
  - topKQueryTerms=30 gives small gain

This script tests combinations to find the optimal joint config.
No index rebuild needed.

Usage:
    python3 test_fielded_combined_dev.py
"""

import json
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT   = Path(__file__).resolve().parents[3]
CONFIG_MAIN = REPO_ROOT / "code/src/main/config/config.yml"
CONFIG_EN   = REPO_ROOT / "code/src/main/config/config_en.yml"
JAR         = REPO_ROOT / "code/target/nexa-0.1-jar-with-dependencies.jar"
RUNS_DIR    = REPO_ROOT / "runs"

LANG_TESTS = {
    "en": {"topics": "datasets/en_dev.json",
           "claims": REPO_ROOT / "datasets/en_dev.json"},
    "fr": {"topics": "datasets/processed/queries/translated/fr_dev_en_translated.json",
           "claims": REPO_ROOT / "datasets/fr_dev.json"},
    "de": {"topics": "datasets/processed/queries/translated/de_dev_en_translated.json",
           "claims": REPO_ROOT / "datasets/de_dev.json"},
}

def make_cfg(label, **kw):
    d = dict(titleBoost=2.0, abstractBoost=1.0, venueBoost=0.0, authorsBoost=0.0,
             queryMode="should", mustTermCount=3, topKQueryTerms=0,
             phraseBoost=0.0, fuzzyBoost=0.0)
    d.update(kw); d["label"] = label; return d

RUNS = [
    # ── reference points ──────────────────────────────────────────────────────
    make_cfg("baseline  t^2  a^1",            titleBoost=2.0, abstractBoost=1.0),
    make_cfg("best-A    t^1  a^1",            titleBoost=1.0, abstractBoost=1.0),
    make_cfg("best-B    t^2  a^3",            titleBoost=2.0, abstractBoost=3.0),

    # ── abstract-preferred ratio × author boost ───────────────────────────────
    make_cfg("t^2 a^3 auth^0.1",             titleBoost=2.0, abstractBoost=3.0, authorsBoost=0.1),
    make_cfg("t^2 a^3 auth^0.3",             titleBoost=2.0, abstractBoost=3.0, authorsBoost=0.3),
    make_cfg("t^2 a^3 auth^0.5",             titleBoost=2.0, abstractBoost=3.0, authorsBoost=0.5),
    make_cfg("t^1 a^1 auth^0.3",             titleBoost=1.0, abstractBoost=1.0, authorsBoost=0.3),

    # ── + venue ───────────────────────────────────────────────────────────────
    make_cfg("t^2 a^3 v^0.3 auth^0.3",      titleBoost=2.0, abstractBoost=3.0, venueBoost=0.3, authorsBoost=0.3),
    make_cfg("t^2 a^3 v^0.5 auth^0.3",      titleBoost=2.0, abstractBoost=3.0, venueBoost=0.5, authorsBoost=0.3),

    # ── + topK pruning ────────────────────────────────────────────────────────
    make_cfg("t^2 a^3 topK=30",             titleBoost=2.0, abstractBoost=3.0, topKQueryTerms=30),
    make_cfg("t^2 a^3 auth^0.3 topK=30",    titleBoost=2.0, abstractBoost=3.0, authorsBoost=0.3, topKQueryTerms=30),
    make_cfg("t^2 a^3 v^0.3 auth^0.3 topK=30",
                                             titleBoost=2.0, abstractBoost=3.0, venueBoost=0.3, authorsBoost=0.3, topKQueryTerms=30),

    # ── fine-tune abstract boost around the sweet spot ────────────────────────
    make_cfg("t^1 a^2",                     titleBoost=1.0, abstractBoost=2.0),
    make_cfg("t^1 a^3",                     titleBoost=1.0, abstractBoost=3.0),
    make_cfg("t^1 a^2 auth^0.3",            titleBoost=1.0, abstractBoost=2.0, authorsBoost=0.3),
    make_cfg("t^1 a^3 auth^0.3",            titleBoost=1.0, abstractBoost=3.0, authorsBoost=0.3),
    make_cfg("t^1 a^4",                     titleBoost=1.0, abstractBoost=4.0),
    make_cfg("t^0 a^1  (abstract only)",    titleBoost=0.001, abstractBoost=1.0),
]

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
    rr5 = sum(1.0 / (r + 1) for r in best.values() if r < 5)
    return rr5 / len(gold)

def run_search(lang, run_id, cfg):
    CONFIG_MAIN.write_text(MAIN_TEMPLATE.format(
        topics=LANG_TESTS[lang]["topics"], run_id=run_id, **cfg))
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector", "-cp", str(JAR),
         "it.unipd.dei.se.nexa.searcher.Searcher"],
        cwd=REPO_ROOT, capture_output=True, text=True)
    run_file = RUNS_DIR / f"{run_id}.txt"
    if proc.returncode != 0 or not run_file.exists():
        print(f"  [{lang.upper()} FAILED]"); print(proc.stderr[-300:]); return None
    return evaluate(run_file, LANG_TESTS[lang]["claims"])

def main():
    if not JAR.exists():
        print(f"ERROR: JAR not found"); sys.exit(1)

    RUNS_DIR.mkdir(exist_ok=True)
    backup_main = CONFIG_MAIN.read_text()
    backup_en   = CONFIG_EN.read_text()
    results = {}
    total = len(RUNS)

    try:
        CONFIG_EN.write_text(EN_CONFIG_BEST)
        print(f"Running {total} combined configs × 3 languages\n")

        for i, cfg in enumerate(RUNS, 1):
            label = cfg["label"]
            results[label] = {}
            avgs = []
            for lang in LANG_TESTS:
                tag = label.replace(" ", "_").replace("^", "x").replace("=","").replace("(","").replace(")","")
                run_id = f"fc-{tag}-{lang}"[:80]
                m = run_search(lang, run_id, cfg)
                if m is not None:
                    results[label][lang] = m
                    avgs.append(m)
            avg = sum(avgs)/len(avgs) if avgs else 0
            en = results[label].get("en", 0)
            fr = results[label].get("fr", 0)
            de = results[label].get("de", 0)
            print(f"[{i:02d}/{total}] {label:<38}  EN={en:.4f} FR={fr:.4f} DE={de:.4f} Avg={avg:.4f}")

    finally:
        CONFIG_EN.write_text(backup_en)
        CONFIG_MAIN.write_text(backup_main)
        print("\nConfigs restored.")

    # Table
    W = 38
    bl = results.get("baseline  t^2  a^1", {})
    baseline_avg = sum(bl.values())/len(bl) if bl else 0
    print(f"\n{'Config':<{W}} | {'EN':>7} | {'FR':>7} | {'DE':>7} | {'Avg':>7} | {'Δ':>6}")
    sep = "-"*(W+1) + "+"+ "-"*9 +"+"+ "-"*9 +"+"+ "-"*9 +"+"+ "-"*9 +"+"+ "-"*8
    print(sep)
    best_avg, best_label = 0, ""
    for cfg in RUNS:
        label = cfg["label"]
        r = results.get(label, {})
        en = r.get("en", float("nan"))
        fr = r.get("fr", float("nan"))
        de = r.get("de", float("nan"))
        vals = [v for v in (en,fr,de) if v==v]
        avg = sum(vals)/len(vals) if vals else float("nan")
        delta = avg - baseline_avg
        if avg > best_avg: best_avg, best_label = avg, label
        marker = " ◄" if avg == best_avg else (" *" if delta > 0.001 else "")
        print(f"{label:<{W}} | {en:>7.4f} | {fr:>7.4f} | {de:>7.4f} | {avg:>7.4f} | {delta:>+6.4f}{marker}")
    print(sep)
    print(f"\nBest: {best_label}  Avg MRR@5={best_avg:.4f}  (+{best_avg-baseline_avg:.4f} vs old baseline)")

if __name__ == "__main__":
    main()
