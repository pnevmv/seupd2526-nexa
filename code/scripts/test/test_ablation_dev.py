#!/usr/bin/env python3
"""Ablation study for the final best lexical config.

Starting point (full best):
  BM25 k1=1.0, b=0.75
  titleBoost=2.0, abstractBoost=3.0
  authorsBoost=1.5, venueBoost=0.0
  topKQueryTerms=30, queryMode=should

Each row removes or changes exactly one component.
No index rebuild needed.

Usage:
    python3 test_ablation_dev.py
"""

import json, subprocess, sys
from pathlib import Path

REPO_ROOT   = Path(__file__).resolve().parents[3]
CONFIG_MAIN = REPO_ROOT / "code/src/main/config/config.yml"
CONFIG_EN   = REPO_ROOT / "code/src/main/config/config_en.yml"
JAR         = REPO_ROOT / "code/target/nexa-0.1-jar-with-dependencies.jar"
RUNS_DIR    = REPO_ROOT / "runs"

LANG_TESTS = {
    "en": ("datasets/en_dev.json",                                                  REPO_ROOT / "datasets/en_dev.json"),
    "fr": ("datasets/processed/queries/translated/fr_dev_en_translated.json",       REPO_ROOT / "datasets/fr_dev.json"),
    "de": ("datasets/processed/queries/translated/de_dev_en_translated.json",       REPO_ROOT / "datasets/de_dev.json"),
}

# ---------------------------------------------------------------------------
# Ablation matrix — best config then one change per row
# ---------------------------------------------------------------------------

BEST = dict(titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0,
            authorsBoost=1.5, topKQueryTerms=30, queryMode="should", mustTermCount=3)

def cfg(label, **overrides):
    d = dict(BEST); d.update(overrides); d["label"] = label; return d

RUNS = [
    cfg("Full best (reference)"),
    # individual ablations
    cfg("− authorsBoost",                         authorsBoost=0.0),
    cfg("− topKQueryTerms (use all terms)",        topKQueryTerms=0),
    cfg("− authorsBoost − topKQueryTerms",         authorsBoost=0.0, topKQueryTerms=0),
    cfg("− abstractBoost (restore abs^1.0)",       abstractBoost=1.0),
    cfg("− abstractBoost − authorsBoost",          abstractBoost=1.0, authorsBoost=0.0),
    cfg("− abstractBoost − topK − authors",        abstractBoost=1.0, authorsBoost=0.0, topKQueryTerms=0),
    # adding venue
    cfg("+ venueBoost=0.3",                        venueBoost=0.3),
    cfg("+ venueBoost=0.5",                        venueBoost=0.5),
    # query mode
    cfg("queryMode=mixed (must=2)",                queryMode="mixed", mustTermCount=2),
    cfg("queryMode=mixed (must=3)",                queryMode="mixed", mustTermCount=3),
    cfg("queryMode=must (all MUST)",               queryMode="must"),
    # simple lexical baseline (no fielded tuning)
    cfg("Simple baseline (t^2 a^1 no auth no topK)",
        titleBoost=2.0, abstractBoost=1.0, authorsBoost=0.0, topKQueryTerms=0,
        venueBoost=0.0, queryMode="should"),
]

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
phraseBoost: 0.0
fuzzyBoost: 0.0
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
    gold = {c["index"]: c["pubkey"] for c in json.loads(Path(claims_file).read_text())}
    best = {}
    with open(run_file) as f:
        for line in f:
            p = line.split()
            if len(p) < 4: continue
            qid, pk, rank = int(p[0]), int(p[2]), int(p[3])
            if gold.get(qid) == pk and qid not in best:
                best[qid] = rank
    rr5 = sum(1.0 / (r + 1) for r in best.values() if r < 5)
    return rr5 / len(gold)

def run_search(lang, run_id, cfg):
    topics, claims = LANG_TESTS[lang]
    CONFIG_MAIN.write_text(MAIN_TEMPLATE.format(topics=topics, run_id=run_id, **cfg))
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector", "-cp", str(JAR),
         "it.unipd.dei.se.nexa.searcher.Searcher"],
        cwd=REPO_ROOT, capture_output=True, text=True)
    rf = RUNS_DIR / f"{run_id}.txt"
    if proc.returncode != 0 or not rf.exists():
        print(f"  [{lang.upper()} FAILED]"); print(proc.stderr[-300:]); return None
    return evaluate(rf, claims)

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    if not JAR.exists():
        print(f"ERROR: JAR not found at {JAR}"); sys.exit(1)

    RUNS_DIR.mkdir(exist_ok=True)
    backup_main = CONFIG_MAIN.read_text()
    backup_en   = CONFIG_EN.read_text()
    results = {}

    try:
        CONFIG_EN.write_text(EN_CONFIG_BEST)
        print(f"Ablation: {len(RUNS)} configs × 3 languages = {len(RUNS)*3} searches\n")

        for i, run in enumerate(RUNS, 1):
            label = run["label"]
            res = {}
            for lang in LANG_TESTS:
                tag = (label.replace(" ", "_").replace("^", "x").replace("=", "")
                            .replace("(", "").replace(")", "").replace("+", "p")
                            .replace("−", "m").replace(",", ""))
                run_id = f"abl-{tag[:50]}-{lang}"
                m = run_search(lang, run_id, run)
                if m is not None:
                    res[lang] = m
            results[label] = res
            avg = sum(res.values()) / len(res) if res else 0
            en, fr, de = res.get("en", 0), res.get("fr", 0), res.get("de", 0)
            print(f"[{i:02d}/{len(RUNS)}] {label:<45}  "
                  f"EN={en:.4f}  FR={fr:.4f}  DE={de:.4f}  Avg={avg:.4f}")

    finally:
        CONFIG_EN.write_text(backup_en)
        CONFIG_MAIN.write_text(backup_main)
        print("\nConfigs restored.")

    # ---------------------------------------------------------------------------
    # Ablation table
    # ---------------------------------------------------------------------------
    ref_label = "Full best (reference)"
    ref = results.get(ref_label, {})
    ref_avg = sum(ref.values()) / len(ref) if ref else 0

    W = 45
    print(f"\n{'='*80}")
    print(f"  ABLATION TABLE  (reference: {ref_label})")
    print(f"  ref: EN={ref.get('en',0):.4f}  FR={ref.get('fr',0):.4f}  "
          f"DE={ref.get('de',0):.4f}  Avg={ref_avg:.4f}")
    print(f"{'='*80}")
    print(f"\n{'Config':<{W}} | {'EN':>7} | {'FR':>7} | {'DE':>7} | {'Avg':>7} | {'Δ Avg':>7}")
    sep = "-" * (W + 1) + "+" + ("-" * 9 + "+") * 4 + "-" * 8
    print(sep)

    for run in RUNS:
        label = run["label"]
        r   = results.get(label, {})
        en  = r.get("en",  float("nan"))
        fr  = r.get("fr",  float("nan"))
        de  = r.get("de",  float("nan"))
        avg = sum(v for v in (en, fr, de) if v == v) / 3
        delta = avg - ref_avg
        marker = "  ◄" if label == ref_label else ("  ↑" if delta > 0.0005 else ("  ↓" if delta < -0.0005 else "  ~"))
        print(f"{label:<{W}} | {en:>7.4f} | {fr:>7.4f} | {de:>7.4f} | {avg:>7.4f} | {delta:>+7.4f}{marker}")

    print(sep)
    print(f"\nComponent contributions (vs Full best):")
    ablations = {
        "authorsBoost":       ("− authorsBoost",       "− authorsBoost − topKQueryTerms"),
        "topKQueryTerms":     ("− topKQueryTerms (use all terms)", "− authorsBoost − topKQueryTerms"),
        "abstractBoost":      ("− abstractBoost (restore abs^1.0)", "Simple baseline (t^2 a^1 no auth no topK)"),
    }
    for comp, (single_abl, _) in ablations.items():
        r = results.get(single_abl, {})
        if r:
            gain = ref_avg - sum(r.values()) / len(r)
            print(f"  {comp:<20}  contributes  {gain:>+.4f} Avg MRR@5")


if __name__ == "__main__":
    main()
