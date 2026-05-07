#!/usr/bin/env python3
"""Quick boundary sweep: auth boost and topK around the best found config."""

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

RUNS = [
    # best from combined test (reference)
    dict(label="t^2 a^3 auth^0.3 topK=30 [ref]",    titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=0.3, topKQueryTerms=30),
    dict(label="t^2 a^3 v^0.3 auth^0.3 topK=30 [ref]", titleBoost=2.0, abstractBoost=3.0, venueBoost=0.3, authorsBoost=0.3, topKQueryTerms=30),
    # push auth boost higher
    dict(label="t^2 a^3 auth^0.5 topK=30",          titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=0.5, topKQueryTerms=30),
    dict(label="t^2 a^3 auth^0.7 topK=30",          titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=0.7, topKQueryTerms=30),
    dict(label="t^2 a^3 auth^1.0 topK=30",          titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=1.0, topKQueryTerms=30),
    # venue + higher auth
    dict(label="t^2 a^3 v^0.3 auth^0.5 topK=30",   titleBoost=2.0, abstractBoost=3.0, venueBoost=0.3, authorsBoost=0.5, topKQueryTerms=30),
    # topK boundary
    dict(label="t^2 a^3 auth^0.3 topK=20",          titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=0.3, topKQueryTerms=20),
    dict(label="t^2 a^3 auth^0.3 topK=25",          titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=0.3, topKQueryTerms=25),
    dict(label="t^2 a^3 auth^0.3 topK=40",          titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=0.3, topKQueryTerms=40),
    dict(label="t^2 a^3 auth^0.3 topK=50",          titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=0.3, topKQueryTerms=50),
    dict(label="t^2 a^3 auth^0.5 topK=40",          titleBoost=2.0, abstractBoost=3.0, venueBoost=0.0, authorsBoost=0.5, topKQueryTerms=40),
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
queryMode: should
mustTermCount: 3
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
    return sum(1.0/(r+1) for r in best.values() if r < 5) / len(gold)

def main():
    if not JAR.exists():
        print("ERROR: JAR not found"); sys.exit(1)
    RUNS_DIR.mkdir(exist_ok=True)
    backup_main = CONFIG_MAIN.read_text()
    backup_en   = CONFIG_EN.read_text()
    results = {}
    try:
        CONFIG_EN.write_text(EN_CONFIG_BEST)
        for i, cfg in enumerate(RUNS, 1):
            label = cfg["label"]
            res = {}
            for lang, (topics, claims) in LANG_TESTS.items():
                tag = label.replace(" ","_").replace("^","x").replace("=","").replace(".","p").replace("[","").replace("]","")
                run_id = f"fb-{tag}-{lang}"[:80]
                CONFIG_MAIN.write_text(MAIN_TEMPLATE.format(
                    topics=topics, run_id=run_id,
                    titleBoost=cfg["titleBoost"], abstractBoost=cfg["abstractBoost"],
                    venueBoost=cfg["venueBoost"], authorsBoost=cfg["authorsBoost"],
                    topKQueryTerms=cfg["topKQueryTerms"]))
                proc = subprocess.run(
                    ["java","--add-modules","jdk.incubator.vector","-cp",str(JAR),
                     "it.unipd.dei.se.nexa.searcher.Searcher"],
                    cwd=REPO_ROOT, capture_output=True, text=True)
                rf = RUNS_DIR / f"{run_id}.txt"
                res[lang] = evaluate(rf, claims) if rf.exists() else 0.0
            results[label] = res
            avg = sum(res.values())/3
            print(f"[{i:02d}/{len(RUNS)}] {label:<45}  EN={res['en']:.4f} FR={res['fr']:.4f} DE={res['de']:.4f} Avg={avg:.4f}")
    finally:
        CONFIG_MAIN.write_text(backup_main)
        CONFIG_EN.write_text(backup_en)
        print("\nConfigs restored.")

    ref_avg = sum(results.get("t^2 a^3 auth^0.3 topK=30 [ref]", {}).values()) / 3
    print(f"\n{'Config':<45} | {'EN':>7} | {'FR':>7} | {'DE':>7} | {'Avg':>7} | {'Δ ref':>7}")
    sep = "-"*46+"+"*1+"-"*9+"+"*1+"-"*9+"+"*1+"-"*9+"+"*1+"-"*9+"+"*1+"-"*8
    print(sep)
    best_avg, best_label = 0, ""
    for cfg in RUNS:
        label = cfg["label"]
        r = results.get(label, {})
        en, fr, de = r.get("en",0), r.get("fr",0), r.get("de",0)
        avg = (en+fr+de)/3
        if avg > best_avg: best_avg, best_label = avg, label
        print(f"{label:<45} | {en:>7.4f} | {fr:>7.4f} | {de:>7.4f} | {avg:>7.4f} | {avg-ref_avg:>+7.4f}")
    print(sep)
    print(f"\nBest overall: {best_label}  Avg={best_avg:.4f}")

if __name__ == "__main__":
    main()
