#!/usr/bin/env python3
"""Query expansion with WordNet on top of the best lexical config:
   Standard + LowerCase + Length + StopList + KStem

For each claim, synonyms are appended to the raw text before it is
analyzed by the Java searcher. The expanded text goes through the same
analyzer pipeline (StandardTokenizer → LowerCase → Length → Stop → KStem),
so only informative synonym tokens survive.

Comparisons:
  baseline   — no expansion
  wn-max1    — up to 1 synonym per word
  wn-max3    — up to 3 synonyms per word
  wn-max5    — up to 5 synonyms per word

Usage:
    python3 test_wordnet_dev.py
"""

import collections
import json
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import nltk
nltk.download("wordnet", quiet=True)
nltk.download("omw-1.4", quiet=True)
from nltk.corpus import wordnet as wn

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
        "topics_orig": REPO_ROOT / "datasets/en_dev.json",
        "claims":      REPO_ROOT / "datasets/en_dev.json",
    },
    "fr": {
        "topics_orig": REPO_ROOT / "datasets/processed/queries/translated/fr_dev_en_translated.json",
        "claims":      REPO_ROOT / "datasets/fr_dev.json",
    },
    "de": {
        "topics_orig": REPO_ROOT / "datasets/processed/queries/translated/de_dev_en_translated.json",
        "claims":      REPO_ROOT / "datasets/de_dev.json",
    },
}

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
"""

# ---------------------------------------------------------------------------
# WordNet expansion (user-provided logic)
# ---------------------------------------------------------------------------

STOP_TERMS = {"the", "a", "an", "of", "in", "on", "and", "or", "to", "is", "are"}

def useful_synonym(candidate: str) -> bool:
    if not candidate:
        return False
    if candidate in STOP_TERMS:
        return False
    if len(candidate) < 3:
        return False
    if any(ch.isdigit() for ch in candidate):
        return False
    return True

def get_ir_synonyms(word: str, pos: str | None = None, max_synonyms: int = 5) -> list[str]:
    pos_map = {"n": wn.NOUN, "v": wn.VERB, "a": wn.ADJ, "r": wn.ADV}
    if word.lower() in STOP_TERMS:
        return []
    wn_pos = pos_map.get(pos) if pos else None
    synsets = wn.synsets(word, pos=wn_pos)
    ranked = []
    seen = {word.lower()}
    for syn in synsets:
        for lemma in syn.lemmas():
            candidate = lemma.name().replace("_", " ").lower().strip()
            if candidate in seen:
                continue
            if not useful_synonym(candidate):
                continue
            seen.add(candidate)
            ranked.append(candidate)
    return ranked[:max_synonyms]

_TOKEN_RE = re.compile(r"[a-zA-Z]+")

def expand_text(text: str, max_synonyms: int) -> str:
    if max_synonyms == 0:
        return text
    extra = []
    for word in _TOKEN_RE.findall(text):
        if len(word) < 3 or word.lower() in STOP_TERMS:
            continue
        synonyms = get_ir_synonyms(word, pos=None, max_synonyms=max_synonyms)
        extra.extend(synonyms)
    if not extra:
        return text
    return text + " " + " ".join(dict.fromkeys(extra))  # deduplicated, order preserved

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

def make_expanded_topics(orig_path: Path, max_synonyms: int) -> Path:
    claims = json.loads(orig_path.read_text())
    expanded = []
    for c in claims:
        ec = dict(c)
        ec["text"] = expand_text(c["text"], max_synonyms)
        expanded.append(ec)
    tmp = tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False)
    json.dump(expanded, tmp, ensure_ascii=False)
    tmp.close()
    return Path(tmp.name)

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

def run_search(topics_path: Path, run_id: str, claims_file: Path) -> dict | None:
    CONFIG_MAIN.write_text(SEARCH_TEMPLATE.format(
        topics=str(topics_path), run_id=run_id))
    proc = subprocess.run(
        ["java", "--add-modules", "jdk.incubator.vector", "-cp", str(JAR), MAIN_CLASS],
        cwd=REPO_ROOT, capture_output=True, text=True)
    run_file = RUNS_DIR / f"{run_id}.txt"
    if proc.returncode != 0 or not run_file.exists():
        print(f"  [SEARCH FAILED]"); print(proc.stderr[-300:]); return None
    return evaluate(run_file, claims_file)

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

EXPANSIONS = [
    {"label": "baseline (no expansion)", "max_syn": 0},
    {"label": "wn-max1 (1 syn/word)",    "max_syn": 1},
    {"label": "wn-max3 (3 syn/word)",    "max_syn": 3},
    {"label": "wn-max5 (5 syn/word)",    "max_syn": 5},
]

def main():
    if not JAR.exists():
        print("ERROR: JAR not found."); sys.exit(1)

    RUNS_DIR.mkdir(exist_ok=True)
    backup_main = CONFIG_MAIN.read_text()
    backup_en   = CONFIG_EN.read_text()

    # Patch config_en.yml to the best config (unchanged for all runs — index built once)
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

    langs = list(LANG_TESTS.keys())
    results = {}   # {label: {lang: metrics}}

    try:
        print("Building index (once, shared across all expansion runs)...")
        if rebuild_index() < 0:
            return

        for exp in EXPANSIONS:
            label   = exp["label"]
            max_syn = exp["max_syn"]
            results[label] = {}
            print(f"\n--- {label} ---")

            for lang in langs:
                lt = LANG_TESTS[lang]
                orig_path = lt["topics_orig"]

                # Build expanded topics file
                topics_path = make_expanded_topics(orig_path, max_syn)

                # Show one example for the first language
                if lang == "en" and max_syn > 0:
                    sample = json.loads(orig_path.read_text())[0]["text"]
                    expanded_sample = expand_text(sample, max_syn)
                    print(f"  [sample claim expansion]")
                    print(f"    orig:  {sample[:100]}")
                    print(f"    expnd: {expanded_sample[:200]}")

                run_id = f"wn{max_syn}-{lang}"
                m = run_search(topics_path, run_id, lt["claims"])
                Path(topics_path).unlink(missing_ok=True)

                if m:
                    results[label][lang] = m
                    print(f"  {lang.upper()}: MRR@5={m['mrr@5']:.4f}  MRR={m['mrr']:.4f}"
                          f"  hit@1={m['hit@1']}  miss={m['missing']}")

    finally:
        CONFIG_EN.write_text(backup_en)
        CONFIG_MAIN.write_text(backup_main)
        print("\nConfigs restored.")

    # ---------------------------------------------------------------------------
    # Results table
    # ---------------------------------------------------------------------------
    W = 26
    hdr = (f"{'Configuration':<{W}} | {'EN MRR@5':>8} {'EN MRR':>7} |"
           f" {'FR MRR@5':>8} {'FR MRR':>7} | {'DE MRR@5':>8} {'DE MRR':>7} | {'Avg MRR@5':>9}")
    sep = "-"*(W+1) + "+" + "-"*18 + "+" + "-"*18 + "+" + "-"*18 + "+" + "-"*11
    print(f"\n{hdr}\n{sep}")
    for exp in EXPANSIONS:
        label = exp["label"]
        r = results.get(label, {})
        en = r.get("en", {}); fr = r.get("fr", {}); de = r.get("de", {})
        e5, e = en.get("mrr@5", 0), en.get("mrr", 0)
        f5, f = fr.get("mrr@5", 0), fr.get("mrr", 0)
        d5, d = de.get("mrr@5", 0), de.get("mrr", 0)
        avg   = (e5 + f5 + d5) / 3
        print(f"{label:<{W}} | {e5:>8.4f} {e:>7.4f} | {f5:>8.4f} {f:>7.4f} | {d5:>8.4f} {d:>7.4f} | {avg:>9.4f}")
    print(sep)


if __name__ == "__main__":
    main()
