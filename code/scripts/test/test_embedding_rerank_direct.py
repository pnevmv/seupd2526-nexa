#!/usr/bin/env python3
"""Embedding reranking experiment on top of the frozen lexical baseline.
Loads BGE-M3 directly — no HTTP server required.

Experiments:
  A. Lexical baseline only
  B. Embedding reranking (rerank top-k by cosine similarity)
  C. Score fusion alpha sweep  (1-α)·BM25norm + α·cosine

Required inputs:
  experiment/doc_embeddings.pkl     (auto-computed if missing)
  runs/abl-Full_best_reference-{en,fr,de}.txt

Usage:
    python3 test_embedding_rerank_direct.py [--top-k 50] [--batch-size 256]
"""

import argparse
import json
import pickle
import sys
import time
from pathlib import Path

import numpy as np
import torch
from sentence_transformers import SentenceTransformer

REPO_ROOT  = Path(__file__).resolve().parents[3]
RUNS_DIR   = REPO_ROOT / "runs"
EMB_FILE   = REPO_ROOT / "experiment" / "doc_embeddings.pkl"
COLLECTION = REPO_ROOT / "datasets/processed/documents/translated/collection_data_en_translated.json"
MODEL_NAME = "BAAI/bge-m3"

LANG_TESTS = {
    "en": {
        "query_file": REPO_ROOT / "datasets/en_dev.json",
        "gold_file":  REPO_ROOT / "datasets/en_dev.json",
        "run_file":   RUNS_DIR  / "abl-Full_best_reference-en.txt",
    },
    "fr": {
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/fr_dev_en_translated.json",
        "gold_file":  REPO_ROOT / "datasets/fr_dev.json",
        "run_file":   RUNS_DIR  / "abl-Full_best_reference-fr.txt",
    },
    "de": {
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/de_dev_en_translated.json",
        "gold_file":  REPO_ROOT / "datasets/de_dev.json",
        "run_file":   RUNS_DIR  / "abl-Full_best_reference-de.txt",
    },
}

ALPHAS_COARSE = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]
ALPHAS_FINE   = [round(a, 2) for a in (x * 0.02 + 0.55 for x in range(16))]  # 0.55..0.85 step 0.02


def mrr_at_5(ranking: dict, gold: dict) -> float:
    rr = 0.0
    for qid, pubkey in gold.items():
        cands = ranking.get(qid, [])
        if pubkey in cands[:5]:
            rr += 1.0 / (cands.index(pubkey) + 1)
    return rr / len(gold)


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


def load_or_compute_doc_embeddings(model: SentenceTransformer, batch_size: int) -> dict:
    if EMB_FILE.exists():
        print(f"Loading doc embeddings from {EMB_FILE} ...", end=" ", flush=True)
        with open(EMB_FILE, "rb") as f:
            embs = pickle.load(f)
        dim = next(iter(embs.values())).shape[0]
        print(f"{len(embs)} docs, dim={dim}")
        return embs

    print(f"Computing doc embeddings (will save to {EMB_FILE}) ...")
    docs   = json.loads(COLLECTION.read_text())
    pubkeys = [d["pubkey"] for d in docs]
    texts   = [f"{d.get('title', '')} {d.get('abstract', '')}".strip() for d in docs]

    t0 = time.time()
    matrix = model.encode(
        texts,
        batch_size=batch_size,
        show_progress_bar=True,
        normalize_embeddings=True,
        convert_to_numpy=True,
    )
    print(f"Encoded {len(texts)} docs in {time.time()-t0:.1f}s, shape={matrix.shape}")

    embs = {pk: matrix[i] for i, pk in enumerate(pubkeys)}
    EMB_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(EMB_FILE, "wb") as f:
        pickle.dump(embs, f, protocol=4)
    print(f"Saved → {EMB_FILE}  ({EMB_FILE.stat().st_size/1e6:.1f} MB)")
    return embs


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--top-k",     type=int, default=50)
    parser.add_argument("--batch-size", type=int, default=256)
    parser.add_argument("--fine", action="store_true", help="Fine-grained alpha sweep (0.55–0.85 step 0.02)")
    args = parser.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}")
    print(f"Loading {MODEL_NAME} ...", end=" ", flush=True)
    t0 = time.time()
    model = SentenceTransformer(MODEL_NAME, device=device)
    model.max_seq_length = 512  # abstracts are ~200-400 tokens; 8192 blows up MPS memory
    print(f"done in {time.time()-t0:.1f}s")

    alphas = ALPHAS_FINE if args.fine else ALPHAS_COARSE
    doc_embs = load_or_compute_doc_embeddings(model, args.batch_size)

    all_results = {}

    for lang, cfg in LANG_TESTS.items():
        print(f"\n=== {lang.upper()} ===")
        queries  = json.loads(cfg["query_file"].read_text())
        gold_raw = json.loads(cfg["gold_file"].read_text())
        gold     = {c["index"]: c["pubkey"] for c in gold_raw}
        run_data = parse_run_file(cfg["run_file"])

        qids = sorted(run_data.keys())
        qid_to_text = {q["index"]: q["text"] for q in queries}

        # Experiment A: lexical baseline
        lex_ranking = {qid: [pk for pk, _, _ in run_data[qid]] for qid in qids}
        mrr_lex = mrr_at_5(lex_ranking, gold)
        print(f"  A. Lexical baseline:          MRR@5 = {mrr_lex:.4f}")

        # Encode queries
        print(f"  Encoding {len(qids)} queries ...", end=" ", flush=True)
        t0 = time.time()
        texts  = [qid_to_text.get(qid, "") for qid in qids]
        q_matrix = model.encode(
            texts,
            batch_size=args.batch_size,
            normalize_embeddings=True,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        qid_to_vec = {qid: q_matrix[i] for i, qid in enumerate(qids)}
        print(f"done in {time.time()-t0:.1f}s")

        # Pre-compute cosine similarities for top-k candidates
        # Embeddings are already L2-normalised, so cosine = dot product
        qid_cosines = {}
        for qid in qids:
            q_vec = qid_to_vec[qid]
            cands = run_data[qid][:args.top_k]
            qid_cosines[qid] = {
                pk: float(np.dot(q_vec, doc_embs[pk])) if pk in doc_embs else 0.0
                for pk, _, _ in cands
            }

        # Experiment B: embedding reranking
        emb_ranking = {}
        for qid in qids:
            cands = run_data[qid][:args.top_k]
            reranked = sorted(cands, key=lambda x: -qid_cosines[qid].get(x[0], 0.0))
            emb_ranking[qid] = [pk for pk, _, _ in reranked]
        mrr_emb = mrr_at_5(emb_ranking, gold)
        print(f"  B. Embedding reranking:       MRR@5 = {mrr_emb:.4f}  (Δ={mrr_emb-mrr_lex:+.4f})")

        # Experiment C: score fusion alpha sweep
        print(f"  C. Score fusion (BM25norm + cosine):")
        best_mrr_f, best_alpha = 0.0, 0.0
        for alpha in alphas:
            fusion_ranking = {}
            for qid in qids:
                cands      = run_data[qid][:args.top_k]
                bm25_norm  = normalize_scores([s for _, _, s in cands])
                cos_norm   = normalize_scores([qid_cosines[qid].get(pk, 0.0) for pk, _, _ in cands])
                combined   = [(1 - alpha) * b + alpha * c for b, c in zip(bm25_norm, cos_norm)]
                order      = sorted(range(len(cands)), key=lambda i: -combined[i])
                fusion_ranking[qid] = [cands[i][0] for i in order]
            mrr_f = mrr_at_5(fusion_ranking, gold)
            if mrr_f > best_mrr_f:
                best_mrr_f, best_alpha = mrr_f, alpha
            marker = "  ◄ best" if mrr_f == best_mrr_f else ""
            print(f"    α={alpha:.1f}  MRR@5={mrr_f:.4f}  (Δ={mrr_f-mrr_lex:+.4f}){marker}")

        all_results[lang] = {
            "lexical":     mrr_lex,
            "emb_rerank":  mrr_emb,
            "fusion_best": best_mrr_f,
            "fusion_alpha": best_alpha,
        }

    # Summary table
    langs = list(all_results.keys())
    print(f"\n{'='*70}")
    sweep = "fine" if args.fine else "coarse"
    print(f"  EMBEDDING RERANKING SUMMARY  (BGE-M3, top_k={args.top_k}, α-sweep={sweep})")
    print(f"{'='*70}")
    print(f"{'Method':<30}", end="")
    for l in langs:
        print(f"  {l.upper():>7}", end="")
    print(f"  {'Avg':>7}")
    print("-" * 70)

    for label, key in [
        ("Lexical baseline",       "lexical"),
        ("Embedding reranking",    "emb_rerank"),
        ("Score fusion (best α)",  "fusion_best"),
    ]:
        vals = [all_results[l][key] for l in langs]
        avg  = sum(vals) / len(vals)
        print(f"{label:<30}", end="")
        for v in vals:
            print(f"  {v:>7.4f}", end="")
        print(f"  {avg:>7.4f}")

    print("-" * 70)
    print("Best fusion α: " + ", ".join(
        f"{l.upper()}={all_results[l]['fusion_alpha']:.1f}" for l in langs))


if __name__ == "__main__":
    main()
