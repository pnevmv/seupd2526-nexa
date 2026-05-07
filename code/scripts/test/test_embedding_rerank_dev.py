#!/usr/bin/env python3
"""Three-way embedding reranking experiment on top of the frozen lexical baseline.

Experiments (no index rebuild, uses existing lexical run files):
  A. Lexical baseline only          — abl-Full_best_reference-{lang}.txt
  B. Embedding reranking            — rerank top-50 by cosine(claim, doc)
  C. Score fusion (alpha sweep)     — (1-α)·BM25norm + α·cosine, α ∈ [0.1..0.9]

Requires:
  - experiment/doc_embeddings.pkl   (from precompute_doc_embeddings.py)
  - runs/abl-Full_best_reference-{en,fr,de}.txt
  - Embedding server running at localhost:8080

Usage:
    python3 test_embedding_rerank_dev.py [--model multi|gemma] [--top-k 50]
"""

import argparse
import json
import pickle
import sys
from pathlib import Path

import numpy as np
import requests

REPO_ROOT = Path(__file__).resolve().parents[3]
RUNS_DIR  = REPO_ROOT / "runs"
EMB_FILE  = REPO_ROOT / "experiment/doc_embeddings.pkl"

LANG_TESTS = {
    "en": {
        "query_file":  REPO_ROOT / "datasets/en_dev.json",
        "gold_file":   REPO_ROOT / "datasets/en_dev.json",
        "run_file":    RUNS_DIR  / "abl-Full_best_reference-en.txt",
        "query_field": "text",
    },
    "fr": {
        "query_file":  REPO_ROOT / "datasets/processed/queries/translated/fr_dev_en_translated.json",
        "gold_file":   REPO_ROOT / "datasets/fr_dev.json",
        "run_file":    RUNS_DIR  / "abl-Full_best_reference-fr.txt",
        "query_field": "text",
    },
    "de": {
        "query_file":  REPO_ROOT / "datasets/processed/queries/translated/de_dev_en_translated.json",
        "gold_file":   REPO_ROOT / "datasets/de_dev.json",
        "run_file":    RUNS_DIR  / "abl-Full_best_reference-de.txt",
        "query_field": "text",
    },
}

ALPHAS = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def mrr_at_5(ranking: dict[int, list[int]], gold: dict[int, int]) -> float:
    """ranking: qid -> [pubkey in rank order]. gold: qid -> pubkey."""
    rr = 0.0
    for qid, pubkey in gold.items():
        cands = ranking.get(qid, [])
        if pubkey in cands[:5]:
            rr += 1.0 / (cands.index(pubkey) + 1)
    return rr / len(gold)

def cosine(a: np.ndarray, b: np.ndarray) -> float:
    na, nb = np.linalg.norm(a), np.linalg.norm(b)
    if na == 0 or nb == 0:
        return 0.0
    return float(np.dot(a, b) / (na * nb))

def embed_texts(texts: list[str], model: str, batch_size: int = 64) -> list[np.ndarray]:
    field = "embedding_gemma" if model == "gemma" else "embedding_multi"
    results = []
    for start in range(0, len(texts), batch_size):
        batch = texts[start:start + batch_size]
        resp = requests.post("http://localhost:8080/process", json={"texts": batch}, timeout=120)
        resp.raise_for_status()
        for r in resp.json()["results"]:
            results.append(np.array(r[field], dtype=np.float32))
    return results

def parse_run_file(path: Path) -> dict[int, list[tuple[int, int, float]]]:
    """Returns qid -> [(pubkey, rank, score), ...] sorted by rank asc."""
    runs: dict[int, list[tuple[int, int, float]]] = {}
    with open(path) as f:
        for line in f:
            parts = line.split()
            if len(parts) < 6:
                continue
            qid, pk, rank, score = int(parts[0]), int(parts[2]), int(parts[3]), float(parts[4])
            runs.setdefault(qid, []).append((pk, rank, score))
    for qid in runs:
        runs[qid].sort(key=lambda x: x[1])
    return runs

def normalize_scores(scores: list[float]) -> list[float]:
    mn, mx = min(scores), max(scores)
    if mx == mn:
        return [0.5] * len(scores)
    return [(s - mn) / (mx - mn) for s in scores]

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", choices=["multi", "gemma"], default="multi")
    parser.add_argument("--top-k", type=int, default=50)
    args = parser.parse_args()

    # Load doc embeddings
    if not EMB_FILE.exists():
        print(f"ERROR: {EMB_FILE} not found — run precompute_doc_embeddings.py first")
        sys.exit(1)
    print(f"Loading document embeddings from {EMB_FILE} ...", end=" ", flush=True)
    with open(EMB_FILE, "rb") as f:
        doc_embs: dict[int, np.ndarray] = pickle.load(f)
    dim = next(iter(doc_embs.values())).shape[0]
    print(f"{len(doc_embs)} docs, dim={dim}")

    # Health check
    try:
        requests.get("http://localhost:8080/health", timeout=5).raise_for_status()
    except Exception as e:
        print(f"ERROR: embedding server not reachable: {e}"); sys.exit(1)

    all_results: dict[str, dict] = {}  # lang -> {method -> MRR@5}

    for lang, cfg in LANG_TESTS.items():
        print(f"\n=== {lang.upper()} ===")
        queries  = json.loads(cfg["query_file"].read_text())
        gold     = {c["index"]: c["pubkey"] for c in json.loads(cfg["gold_file"].read_text())}
        run_data = parse_run_file(cfg["run_file"])

        # Build query index: qid -> text
        qid_to_text = {q["index"]: q[cfg["query_field"]] for q in queries}

        # Limit to queries present in run file
        qids_in_run = sorted(run_data.keys())

        # --- Experiment A: Lexical baseline ---
        lex_ranking = {qid: [pk for pk, _, _ in run_data[qid]] for qid in qids_in_run}
        mrr_lex = mrr_at_5(lex_ranking, gold)
        print(f"  A. Lexical baseline:          MRR@5 = {mrr_lex:.4f}")

        # Embed all queries
        print(f"  Embedding {len(qids_in_run)} queries ...", end=" ", flush=True)
        texts  = [qid_to_text.get(qid, "") for qid in qids_in_run]
        q_vecs = embed_texts(texts, args.model)
        qid_to_vec = {qid: vec for qid, vec in zip(qids_in_run, q_vecs)}
        print("done")

        # Pre-compute cosine similarities
        # For each qid, compute cosine with each of its top-k candidates
        qid_cosines: dict[int, dict[int, float]] = {}
        for qid in qids_in_run:
            q_vec = qid_to_vec[qid]
            cands = run_data[qid][:args.top_k]
            qid_cosines[qid] = {
                pk: cosine(q_vec, doc_embs[pk]) if pk in doc_embs else 0.0
                for pk, _, _ in cands
            }

        # --- Experiment B: Embedding reranking ---
        emb_ranking = {}
        for qid in qids_in_run:
            cands = run_data[qid][:args.top_k]
            reranked = sorted(cands, key=lambda x: -qid_cosines[qid].get(x[0], 0.0))
            emb_ranking[qid] = [pk for pk, _, _ in reranked]
        mrr_emb = mrr_at_5(emb_ranking, gold)
        print(f"  B. Embedding reranking:       MRR@5 = {mrr_emb:.4f}  (Δ={mrr_emb-mrr_lex:+.4f})")

        # --- Experiment C: Score fusion alpha sweep ---
        print(f"  C. Score fusion (BM25norm + cosine):")
        best_fusion_mrr, best_alpha = 0.0, 0.0
        fusion_results = []
        for alpha in ALPHAS:
            fusion_ranking = {}
            for qid in qids_in_run:
                cands = run_data[qid][:args.top_k]
                bm25_scores   = [s for _, _, s in cands]
                bm25_norm     = normalize_scores(bm25_scores)
                cos_scores    = [qid_cosines[qid].get(pk, 0.0) for pk, _, _ in cands]
                cos_norm      = normalize_scores(cos_scores)
                combined      = [(1 - alpha) * b + alpha * c
                                 for b, c in zip(bm25_norm, cos_norm)]
                order         = sorted(range(len(cands)), key=lambda i: -combined[i])
                fusion_ranking[qid] = [cands[i][0] for i in order]
            mrr_f = mrr_at_5(fusion_ranking, gold)
            fusion_results.append((alpha, mrr_f))
            if mrr_f > best_fusion_mrr:
                best_fusion_mrr, best_alpha = mrr_f, alpha
            marker = "  ◄ best" if mrr_f == best_fusion_mrr else ""
            print(f"    α={alpha:.1f}  MRR@5={mrr_f:.4f}  (Δ={mrr_f-mrr_lex:+.4f}){marker}")

        all_results[lang] = {
            "lexical":   mrr_lex,
            "emb_rerank": mrr_emb,
            "fusion_best": best_fusion_mrr,
            "fusion_alpha": best_alpha,
        }

    # ---------------------------------------------------------------------------
    # Summary table
    # ---------------------------------------------------------------------------
    print(f"\n{'='*70}")
    print(f"  EMBEDDING RERANKING SUMMARY  (model={args.model})")
    print(f"{'='*70}")
    langs = list(all_results.keys())
    print(f"{'Method':<30}", end="")
    for l in langs:
        print(f"  {l.upper():>7}", end="")
    print(f"  {'Avg':>7}")
    print("-" * 70)

    methods = [
        ("Lexical baseline",   "lexical"),
        ("Embedding reranking","emb_rerank"),
        (f"Score fusion (best α)", "fusion_best"),
    ]
    for label, key in methods:
        vals = [all_results[l][key] for l in langs]
        avg  = sum(vals) / len(vals)
        print(f"{label:<30}", end="")
        for v in vals:
            print(f"  {v:>7.4f}", end="")
        print(f"  {avg:>7.4f}")

    print("-" * 70)
    print(f"Best fusion α per language: " + ", ".join(
        f"{l.upper()}={all_results[l]['fusion_alpha']:.1f}" for l in langs))


if __name__ == "__main__":
    main()
