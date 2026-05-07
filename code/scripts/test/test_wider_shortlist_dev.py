#!/usr/bin/env python3
"""Test BM25+BGE-M3 fusion with a wider BM25 candidate pool on dev sets.

Patches config.yml to retrieve top-N candidates (default 200), runs the Java
Searcher for EN/FR/DE dev, applies BGE-M3 fusion at alpha=0.70, and reports
MRR@5 compared to the frozen 50-candidate fusion baseline (0.5290 avg).

Usage:
    python3 test_wider_shortlist_dev.py [--max-docs 200] [--alpha 0.70]
"""

import argparse
import json
import pickle
import subprocess
import time
from pathlib import Path

import numpy as np
import torch
from sentence_transformers import SentenceTransformer

REPO_ROOT  = Path(__file__).resolve().parents[3]
CONFIG     = REPO_ROOT / "code/src/main/config/config.yml"
JAR        = REPO_ROOT / "code/target/nexa-0.1-jar-with-dependencies.jar"
MAIN_CLASS = "it.unipd.dei.se.nexa.searcher.Searcher"
EMB_FILE   = REPO_ROOT / "experiment" / "doc_embeddings.pkl"
MODEL_NAME = "BAAI/bge-m3"

LANG_CONFIGS = {
    "en": {
        "topics":     "datasets/en_dev.json",
        "query_file": REPO_ROOT / "datasets/en_dev.json",
        "gold_file":  REPO_ROOT / "datasets/en_dev.json",
        "run_id":     "wider-en-dev-lexical",
    },
    "fr": {
        "topics":     "datasets/processed/queries/translated/fr_dev_en_translated.json",
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/fr_dev_en_translated.json",
        "gold_file":  REPO_ROOT / "datasets/fr_dev.json",
        "run_id":     "wider-fr-dev-lexical",
    },
    "de": {
        "topics":     "datasets/processed/queries/translated/de_dev_en_translated.json",
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/de_dev_en_translated.json",
        "gold_file":  REPO_ROOT / "datasets/de_dev.json",
        "run_id":     "wider-de-dev-lexical",
    },
}

# Frozen baselines from previous experiments
REF = {
    "lexical": {"en": 0.5172, "fr": 0.5145, "de": 0.3841},
    "fusion50": {"en": 0.5768, "fr": 0.5770, "de": 0.4331},
}


def patch_and_run(lang: str, cfg: dict, max_docs: int) -> dict:
    """Patch config and run Java Searcher. Returns {qid: [(pk, rank, score)]}."""
    original = CONFIG.read_text()
    lines = []
    for line in original.splitlines():
        if line.startswith("topics:"):
            lines.append(f"topics: {cfg['topics']}")
        elif line.startswith("runID:"):
            lines.append(f"runID: {cfg['run_id']}")
        elif line.startswith("runPath:"):
            lines.append("runPath: runs")
        elif line.startswith("maxDocsRetrieved:"):
            lines.append(f"maxDocsRetrieved: {max_docs}")
        elif line.startswith("reRank:"):
            lines.append("reRank: false")
        elif line.startswith("searchMode:"):
            lines.append("searchMode: lexical")
        elif line.startswith("enableQueryExpansion:"):
            lines.append("enableQueryExpansion: false")
        elif line.startswith("translateNonEnglishClaimsToEnglish:"):
            lines.append("translateNonEnglishClaimsToEnglish: false")
        elif line.startswith("translateNonEnglishPublicationsToEnglish:"):
            lines.append("translateNonEnglishPublicationsToEnglish: false")
        else:
            lines.append(line)
    CONFIG.write_text("\n".join(lines) + "\n")

    try:
        t0 = time.time()
        proc = subprocess.run(
            ["java", "--add-modules", "jdk.incubator.vector",
             "-cp", str(JAR), MAIN_CLASS],
            cwd=REPO_ROOT, capture_output=True, text=True,
        )
        elapsed = time.time() - t0
        if proc.returncode != 0:
            print(f"  FAILED (exit {proc.returncode})")
            print(proc.stderr[:1000])
            return {}

        run_file = REPO_ROOT / "runs" / f"{cfg['run_id']}.txt"
        runs = {}
        with open(run_file) as f:
            for line in f:
                parts = line.split()
                if len(parts) < 6:
                    continue
                qid   = int(parts[0])
                pk    = int(parts[2])
                rank  = int(parts[3])
                score = float(parts[4])
                runs.setdefault(qid, []).append((pk, rank, score))
        for qid in runs:
            runs[qid].sort(key=lambda x: x[1])

        total_lines = sum(len(v) for v in runs.values())
        print(f"  Lexical: {len(runs)} queries, {total_lines} lines  [{elapsed:.0f}s]")
        return runs
    finally:
        CONFIG.write_text(original)


def normalize_scores(scores: list) -> list:
    mn, mx = min(scores), max(scores)
    if mx == mn:
        return [0.5] * len(scores)
    return [(s - mn) / (mx - mn) for s in scores]


def mrr_at_5(ranking: dict, gold: dict) -> float:
    rr = 0.0
    for qid, pubkey in gold.items():
        cands = ranking.get(qid, [])
        if pubkey in cands[:5]:
            rr += 1.0 / (cands.index(pubkey) + 1)
    return rr / len(gold)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--max-docs", type=int, default=200)
    parser.add_argument("--alpha",    type=float, default=0.70)
    parser.add_argument("--batch-size", type=int, default=32)
    args = parser.parse_args()

    if not JAR.exists():
        print("ERROR: JAR not found. Run: cd code && mvn package -DskipTests")
        return

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}")
    print(f"Loading {MODEL_NAME} ...", end=" ", flush=True)
    t0 = time.time()
    model = SentenceTransformer(MODEL_NAME, device=device)
    model.max_seq_length = 512
    print(f"done in {time.time()-t0:.1f}s")

    print(f"Loading doc embeddings ...", end=" ", flush=True)
    with open(EMB_FILE, "rb") as f:
        doc_embs: dict = pickle.load(f)
    print(f"{len(doc_embs)} docs\n")

    results = {}
    for lang, cfg in LANG_CONFIGS.items():
        print(f"=== {lang.upper()} (maxDocs={args.max_docs}) ===")

        run_data = patch_and_run(lang, cfg, args.max_docs)
        if not run_data:
            continue

        queries  = json.loads(cfg["query_file"].read_text())
        gold_raw = json.loads(cfg["gold_file"].read_text())
        gold     = {c["index"]: c["pubkey"] for c in gold_raw}
        qids     = sorted(run_data.keys())
        qid_to_text = {q["index"]: q["text"] for q in queries}

        # Lexical-only MRR@5 on the wider run
        lexical_ranking = {qid: [pk for pk, _, _ in run_data[qid]] for qid in qids}
        mrr_lex = mrr_at_5(lexical_ranking, gold)

        # Encode queries
        print(f"  Encoding {len(qids)} queries ...", end=" ", flush=True)
        t0 = time.time()
        texts    = [qid_to_text.get(qid, "") for qid in qids]
        q_matrix = model.encode(
            texts,
            batch_size=args.batch_size,
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        qid_to_vec = {qid: q_matrix[i] for i, qid in enumerate(qids)}
        print(f"done in {time.time()-t0:.1f}s")

        # Fuse
        fusion_ranking = {}
        for qid in qids:
            cands     = run_data[qid]
            q_vec     = qid_to_vec[qid]
            bm25_norm = normalize_scores([s for _, _, s in cands])
            cos_scores = [
                float(np.dot(q_vec, doc_embs[pk])) if pk in doc_embs else 0.0
                for pk, _, _ in cands
            ]
            cos_norm = normalize_scores(cos_scores)
            combined = [
                (1 - args.alpha) * b + args.alpha * c
                for b, c in zip(bm25_norm, cos_norm)
            ]
            order = sorted(range(len(cands)), key=lambda i: -combined[i])
            fusion_ranking[qid] = [cands[i][0] for i in order]

        mrr_fused = mrr_at_5(fusion_ranking, gold)
        mrr_ref50 = REF["fusion50"][lang]
        delta = mrr_fused - mrr_ref50

        print(f"  Lexical top-{args.max_docs}:  MRR@5 = {mrr_lex:.4f}  (ref lexical top-50: {REF['lexical'][lang]:.4f})")
        print(f"  Fusion  top-{args.max_docs}:  MRR@5 = {mrr_fused:.4f}  (ref fusion  top-50: {mrr_ref50:.4f})  Δ={delta:+.4f}")
        results[lang] = {"lexical": mrr_lex, "fusion": mrr_fused}

    if len(results) == 3:
        avg_fused = sum(results[l]["fusion"] for l in results) / 3
        avg_ref   = sum(REF["fusion50"][l] for l in results) / 3
        print(f"\n{'='*56}")
        print(f"  Avg fusion top-{args.max_docs}: {avg_fused:.4f}  (ref top-50: {avg_ref:.4f})  Δ={avg_fused-avg_ref:+.4f}")
        print(f"{'='*56}")


if __name__ == "__main__":
    main()
