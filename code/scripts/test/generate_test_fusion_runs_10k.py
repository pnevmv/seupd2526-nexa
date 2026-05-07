#!/usr/bin/env python3
"""Full 10k BM25+BGE-M3 fusion test run files at a given alpha.

Reads nexa-*-test-baseline.txt (top-1000 BM25), computes BGE-M3 cosine over
all 10k docs, fuses at alpha=0.80, and writes TREC run files.

Usage:
    python3 generate_test_fusion_runs_10k.py [--alpha 0.80] [--batch-size 32]
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
        "out_file":   REPO_ROOT / "runs/hybrid-en-test-bge-fusion-10k.txt",
        "run_tag":    "hybrid-en-test-bge-fusion-10k",
    },
    "fr": {
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/fr_test_en_translated.json",
        "run_file":   REPO_ROOT / "runs/nexa-fr-test-baseline.txt",
        "out_file":   REPO_ROOT / "runs/hybrid-fr-test-bge-fusion-10k.txt",
        "run_tag":    "hybrid-fr-test-bge-fusion-10k",
    },
    "de": {
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/de_test_en_translated.json",
        "run_file":   REPO_ROOT / "runs/nexa-de-test-baseline.txt",
        "out_file":   REPO_ROOT / "runs/hybrid-de-test-bge-fusion-10k.txt",
        "run_tag":    "hybrid-de-test-bge-fusion-10k",
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
            score = float(parts[4])
            runs.setdefault(qid, {})[pk] = score
    return runs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--alpha",      type=float, default=0.80)
    parser.add_argument("--batch-size", type=int,   default=32)
    args = parser.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}  alpha={args.alpha}")
    print(f"Loading {MODEL_NAME} ...", end=" ", flush=True)
    t0 = time.time()
    model = SentenceTransformer(MODEL_NAME, device=device)
    model.max_seq_length = 512
    print(f"done in {time.time()-t0:.1f}s")

    print(f"Loading doc embeddings ...", end=" ", flush=True)
    with open(EMB_FILE, "rb") as f:
        doc_embs: dict = pickle.load(f)
    all_pks    = list(doc_embs.keys())
    doc_matrix = np.stack([doc_embs[pk] for pk in all_pks])
    print(f"{len(all_pks)} docs")

    for lang, cfg in LANG_CONFIGS.items():
        print(f"\n=== {lang.upper()} test ===")
        queries  = json.loads(cfg["query_file"].read_text())
        bm25_map = parse_run_file(cfg["run_file"])
        qids     = sorted(bm25_map.keys())
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
        print(f"done in {time.time()-t0:.1f}s")

        print(f"  Computing cosine + fusing ...", end=" ", flush=True)
        t0 = time.time()
        cos_matrix = q_matrix @ doc_matrix.T  # (n_queries, 10k)

        out_path = cfg["out_file"]
        written  = 0
        with open(out_path, "w") as out:
            for i, qid in enumerate(qids):
                cos_scores = cos_matrix[i]

                mn_c, mx_c = float(cos_scores.min()), float(cos_scores.max())
                cos_norm = (cos_scores - mn_c) / (mx_c - mn_c) if mx_c > mn_c else np.full(len(all_pks), 0.5)

                bm25_scores_dict = bm25_map.get(qid, {})
                bm25_vals = list(bm25_scores_dict.values())
                mn_b, mx_b = (min(bm25_vals), max(bm25_vals)) if bm25_vals else (0.0, 1.0)
                bm25_norm = np.zeros(len(all_pks), dtype=np.float32)
                for j, pk in enumerate(all_pks):
                    raw = bm25_scores_dict.get(pk, 0.0)
                    bm25_norm[j] = (raw - mn_b) / (mx_b - mn_b) if mx_b > mn_b else 0.0

                combined = (1 - args.alpha) * bm25_norm + args.alpha * cos_norm
                order    = np.argsort(-combined)

                tag = cfg["run_tag"]
                for rank, j in enumerate(order):
                    out.write(f"{qid} Q0 {all_pks[j]} {rank} {combined[j]:.6f} {tag}\n")
                    written += 1

        print(f"done in {time.time()-t0:.1f}s")
        print(f"  Written {written} lines → {out_path.name}")

    print("\nDone.")


if __name__ == "__main__":
    main()
