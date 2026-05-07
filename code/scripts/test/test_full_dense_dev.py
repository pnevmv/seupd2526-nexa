#!/usr/bin/env python3
"""Full 10k dense retrieval experiment on dev sets.

Tests two variants on top of the wider BM25 runs (top-1000):
  A) Pure BGE-M3: rank all 10k docs by cosine similarity alone
  B) Full fusion: (1-alpha)*BM25_norm + alpha*cosine over all 10k docs
     (BM25 score = 0 for docs not in the top-1000 BM25 pool)

Compares against frozen baselines:
  - Lexical top-50:   avg MRR@5 = 0.4719
  - Fusion  top-50:   avg MRR@5 = 0.5290
  - Fusion  top-1000: avg MRR@5 = 0.5398

Usage:
    python3 test_full_dense_dev.py [--alpha 0.70] [--batch-size 32]
    python3 test_full_dense_dev.py --sweep
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
        "query_file": REPO_ROOT / "datasets/en_dev.json",
        "gold_file":  REPO_ROOT / "datasets/en_dev.json",
        "run_file":   REPO_ROOT / "runs/wider-en-dev-lexical.txt",
    },
    "fr": {
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/fr_dev_en_translated.json",
        "gold_file":  REPO_ROOT / "datasets/fr_dev.json",
        "run_file":   REPO_ROOT / "runs/wider-fr-dev-lexical.txt",
    },
    "de": {
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/de_dev_en_translated.json",
        "gold_file":  REPO_ROOT / "datasets/de_dev.json",
        "run_file":   REPO_ROOT / "runs/wider-de-dev-lexical.txt",
    },
}

REF = {
    "lexical-50":  {"en": 0.5172, "fr": 0.5145, "de": 0.3841, "avg": 0.4719},
    "fusion-50":   {"en": 0.5768, "fr": 0.5770, "de": 0.4331, "avg": 0.5290},
    "fusion-1000": {"en": 0.5853, "fr": 0.5892, "de": 0.4449, "avg": 0.5398},
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
    return runs  # {qid: {pk: bm25_score}}


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
    parser.add_argument("--alpha",      type=float, default=0.70)
    parser.add_argument("--batch-size", type=int,   default=32)
    parser.add_argument("--sweep",      action="store_true",
                        help="Sweep alpha from 0.1 to 0.9 in 0.05 steps")
    args = parser.parse_args()

    alphas = [round(a * 0.05, 2) for a in range(2, 20)] if args.sweep else [args.alpha]

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
    all_pks  = list(doc_embs.keys())
    doc_matrix = np.stack([doc_embs[pk] for pk in all_pks])  # (10k, 1024)
    print(f"{len(all_pks)} docs, matrix {doc_matrix.shape}")

    # Per-language: store precomputed per-query (cos_norm, bm25_norm) arrays
    lang_data = {}

    for lang, cfg in LANG_CONFIGS.items():
        print(f"\n=== {lang.upper()} ===")
        queries  = json.loads(cfg["query_file"].read_text())
        gold_raw = json.loads(cfg["gold_file"].read_text())
        gold     = {c["index"]: c["pubkey"] for c in gold_raw}
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

        print(f"  Computing cosine over all 10k docs ...", end=" ", flush=True)
        t0 = time.time()
        cos_matrix = q_matrix @ doc_matrix.T  # (n_queries, 10k)
        print(f"done in {time.time()-t0:.2f}s")

        # Precompute normalised arrays per query (alpha-independent)
        print(f"  Normalising scores ...", end=" ", flush=True)
        cos_norm_list  = []
        bm25_norm_list = []
        pure_ranking   = {}

        for i, qid in enumerate(qids):
            cos_scores = cos_matrix[i]

            # Pure BGE-M3 ranking (no alpha needed)
            order_pure = np.argsort(-cos_scores)
            pure_ranking[qid] = [all_pks[j] for j in order_pure[:50]]

            # Normalise cosine
            mn_c, mx_c = float(cos_scores.min()), float(cos_scores.max())
            cos_norm = (cos_scores - mn_c) / (mx_c - mn_c) if mx_c > mn_c else np.full(len(all_pks), 0.5)

            # Normalise BM25 (0 for docs not retrieved)
            bm25_scores_dict = bm25_map.get(qid, {})
            bm25_vals = list(bm25_scores_dict.values())
            mn_b, mx_b = (min(bm25_vals), max(bm25_vals)) if bm25_vals else (0.0, 1.0)
            bm25_norm = np.zeros(len(all_pks), dtype=np.float32)
            for j, pk in enumerate(all_pks):
                raw = bm25_scores_dict.get(pk, 0.0)
                bm25_norm[j] = (raw - mn_b) / (mx_b - mn_b) if mx_b > mn_b else 0.0

            cos_norm_list.append(cos_norm)
            bm25_norm_list.append(bm25_norm)

        print("done")

        mrr_pure = mrr_at_5(pure_ranking, gold)
        print(f"  Pure BGE-M3 (10k): MRR@5 = {mrr_pure:.4f}")

        lang_data[lang] = {
            "gold":           gold,
            "qids":           qids,
            "pure_mrr":       mrr_pure,
            "cos_norm_list":  cos_norm_list,
            "bm25_norm_list": bm25_norm_list,
        }

    # Alpha sweep — cheap, no re-encoding needed
    langs = list(lang_data.keys())
    print(f"\n{'='*72}")
    print(f"  ALPHA SWEEP — Full fusion (BM25 top-1000 + BGE-M3 all 10k)")
    print(f"{'='*72}")
    header = f"  {'alpha':>6}" + "".join(f"  {l.upper():>7}" for l in langs) + f"  {'Avg':>7}"
    print(header)
    print("-" * 72)

    best_avg, best_alpha = 0.0, 0.0
    sweep_results = {}

    for alpha in alphas:
        row_vals = {}
        for lang in langs:
            d    = lang_data[lang]
            gold = d["gold"]
            qids = d["qids"]
            fused_ranking = {}
            for i, qid in enumerate(qids):
                combined = (1 - alpha) * d["bm25_norm_list"][i] + alpha * d["cos_norm_list"][i]
                order    = np.argsort(-combined)
                fused_ranking[qid] = [all_pks[j] for j in order[:50]]
            row_vals[lang] = mrr_at_5(fused_ranking, gold)

        avg = sum(row_vals[l] for l in langs) / len(langs)
        sweep_results[alpha] = {"langs": row_vals, "avg": avg}
        marker = " ◄ best" if avg > best_avg else ""
        if avg > best_avg:
            best_avg, best_alpha = avg, alpha
        print(f"  {alpha:>6.2f}" + "".join(f"  {row_vals[l]:>7.4f}" for l in langs) + f"  {avg:>7.4f}{marker}")

    print("-" * 72)
    # Reference rows
    for label, ref_key in [("Fusion top-50 (α=0.70)", "fusion-50"), ("Fusion top-1000 (α=0.70)", "fusion-1000")]:
        print(f"  {'ref':>6}  {label:<20}" + "".join(f"  {REF[ref_key][l]:>7.4f}" for l in langs) + f"  {REF[ref_key]['avg']:>7.4f}")

    print(f"\n  Best alpha: {best_alpha:.2f}  →  avg MRR@5 = {best_avg:.4f}"
          f"  (Δ vs fusion-1000 = {best_avg - REF['fusion-1000']['avg']:+.4f})")


if __name__ == "__main__":
    main()
