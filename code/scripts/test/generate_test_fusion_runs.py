#!/usr/bin/env python3
"""Apply BM25+BGE-M3 score fusion at alpha=0.70 for all three test sets.

Reads test lexical run files + precomputed doc embeddings, encodes queries with
BGE-M3, applies (1-alpha)*BM25norm + alpha*cosine, and writes TREC output.

Usage:
    python3 generate_test_fusion_runs.py [--alpha 0.70] [--top-k 50] [--batch-size 32]
"""

import argparse
import json
import pickle
import time
from pathlib import Path

import numpy as np
import torch
from sentence_transformers import SentenceTransformer

REPO_ROOT  = Path(__file__).resolve().parents[3]
EMB_FILE   = REPO_ROOT / "experiment" / "doc_embeddings.pkl"
MODEL_NAME = "BAAI/bge-m3"

LANG_CONFIGS = {
    "en": {
        "query_file": REPO_ROOT / "datasets/final_en_test.json",
        "run_file":   REPO_ROOT / "runs/nexa-en-test-baseline.txt",
        "out_file":   REPO_ROOT / "runs/hybrid-en-test-bge-fusion.txt",
        "run_tag":    "hybrid-en-test-bge-fusion",
    },
    "fr": {
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/fr_test_en_translated.json",
        "run_file":   REPO_ROOT / "runs/nexa-fr-test-baseline.txt",
        "out_file":   REPO_ROOT / "runs/hybrid-fr-test-bge-fusion.txt",
        "run_tag":    "hybrid-fr-test-bge-fusion",
    },
    "de": {
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/de_test_en_translated.json",
        "run_file":   REPO_ROOT / "runs/nexa-de-test-baseline.txt",
        "out_file":   REPO_ROOT / "runs/hybrid-de-test-bge-fusion.txt",
        "run_tag":    "hybrid-de-test-bge-fusion",
    },
}


def parse_run_file(path: Path) -> dict:
    runs = {}
    with open(path) as f:
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
    return runs


def normalize_scores(scores: list) -> list:
    mn, mx = min(scores), max(scores)
    if mx == mn:
        return [0.5] * len(scores)
    return [(s - mn) / (mx - mn) for s in scores]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--alpha",      type=float, default=0.70)
    parser.add_argument("--top-k",      type=int,   default=50)
    parser.add_argument("--batch-size", type=int,   default=32)
    args = parser.parse_args()

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
    print(f"{len(doc_embs)} docs")

    for lang, cfg in LANG_CONFIGS.items():
        print(f"\n=== {lang.upper()} test ===")
        queries  = json.loads(cfg["query_file"].read_text())
        run_data = parse_run_file(cfg["run_file"])
        qids     = sorted(run_data.keys())
        qid_to_text = {q["index"]: q["text"] for q in queries}

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

        out_path = cfg["out_file"]
        tag      = cfg["run_tag"]
        written  = 0

        with open(out_path, "w") as out:
            for qid in qids:
                cands     = run_data[qid][:args.top_k]
                q_vec     = qid_to_vec[qid]

                bm25_norm = normalize_scores([s for _, _, s in cands])
                cos_scores = [
                    float(np.dot(q_vec, doc_embs[pk])) if pk in doc_embs else 0.0
                    for pk, _, _ in cands
                ]
                cos_norm  = normalize_scores(cos_scores)
                combined  = [
                    (1 - args.alpha) * b + args.alpha * c
                    for b, c in zip(bm25_norm, cos_norm)
                ]

                order = sorted(range(len(cands)), key=lambda i: -combined[i])
                for rank, i in enumerate(order):
                    pk    = cands[i][0]
                    score = combined[i]
                    out.write(f"{qid} Q0 {pk} {rank} {score:.6f} {tag}\n")
                    written += 1

        print(f"  Written {written} lines → {out_path.name}")

    print("\nDone. Run files:")
    for cfg in LANG_CONFIGS.values():
        p = cfg["out_file"]
        print(f"  {p.name}  ({p.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
