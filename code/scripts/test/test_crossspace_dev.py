#!/usr/bin/env python3
"""Exploratory cross-space experiment: SPECTER2 documents × BGE-M3 queries.

SPECTER2 proximity adapter encodes documents; BGE-M3 encodes queries.
Cosine similarity is applied across the two embedding spaces — this is
geometrically impure and the result cannot be a production method, but it
isolates whether SPECTER2's document representations carry useful signal
independently of its weak query-side encoder.

Requires:
  experiment/doc_embeddings_specter2.pkl  (from test_specter2_dev.py)
  experiment/doc_embeddings.pkl           (BGE-M3, for reference)
  runs/abl-Full_best_reference-{en,fr,de}.txt

Usage:
    python3 test_crossspace_dev.py [--batch-size 32]
"""

import argparse
import json
import pickle
import time
from pathlib import Path

import numpy as np
import torch
from sentence_transformers import SentenceTransformer
from sklearn.decomposition import PCA

REPO_ROOT      = Path(__file__).resolve().parents[3]
RUNS_DIR       = REPO_ROOT / "runs"
SP2_EMB_FILE   = REPO_ROOT / "experiment" / "doc_embeddings_specter2.pkl"
BGE_EMB_FILE   = REPO_ROOT / "experiment" / "doc_embeddings.pkl"
BGE_MODEL      = "BAAI/bge-m3"

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

ALPHAS = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]

# Reference numbers from previous experiments
REF = {
    "bge_fusion":  {"en": 0.5768, "fr": 0.5770, "de": 0.4331, "avg": 0.5290},
    "sp2_fusion":  {"en": 0.5524, "fr": 0.5475, "de": 0.4150, "avg": 0.5050},
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


def mrr_at_5(ranking: dict, gold: dict) -> float:
    rr = 0.0
    for qid, pubkey in gold.items():
        cands = ranking.get(qid, [])
        if pubkey in cands[:5]:
            rr += 1.0 / (cands.index(pubkey) + 1)
    return rr / len(gold)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch-size", type=int, default=32)
    args = parser.parse_args()

    for path, name in [(SP2_EMB_FILE, "SPECTER2 doc embeddings"),
                       (BGE_EMB_FILE, "BGE-M3 doc embeddings")]:
        if not path.exists():
            print(f"ERROR: {name} not found at {path}")
            print("Run test_specter2_dev.py and test_embedding_rerank_direct.py first.")
            return

    print(f"Loading SPECTER2 doc embeddings ...", end=" ", flush=True)
    with open(SP2_EMB_FILE, "rb") as f:
        sp2_doc_embs: dict = pickle.load(f)
    sp2_dim = next(iter(sp2_doc_embs.values())).shape[0]  # 768
    print(f"{len(sp2_doc_embs)} docs, dim={sp2_dim}")

    # Fit PCA on BGE-M3 doc embeddings (10k samples, 1024-dim → 768-dim).
    # This transform is applied to all query sets so the projection is consistent.
    print(f"Loading BGE-M3 doc embeddings to fit PCA {1024}→{sp2_dim} ...", end=" ", flush=True)
    with open(BGE_EMB_FILE, "rb") as f:
        bge_doc_embs: dict = pickle.load(f)
    bge_doc_matrix = np.stack(list(bge_doc_embs.values()))  # (10000, 1024)
    pca = PCA(n_components=sp2_dim, random_state=42)
    pca.fit(bge_doc_matrix)
    print(f"done (explained var: {pca.explained_variance_ratio_.sum():.3f})")
    del bge_doc_matrix, bge_doc_embs

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}")
    print(f"Loading BGE-M3 for query encoding ...", end=" ", flush=True)
    t0 = time.time()
    bge_model = SentenceTransformer(BGE_MODEL, device=device)
    bge_model.max_seq_length = 512
    print(f"done in {time.time()-t0:.1f}s")

    all_results = {}

    for lang, cfg in LANG_TESTS.items():
        print(f"\n=== {lang.upper()} ===")
        queries  = json.loads(cfg["query_file"].read_text())
        gold_raw = json.loads(cfg["gold_file"].read_text())
        gold     = {c["index"]: c["pubkey"] for c in gold_raw}
        run_data = parse_run_file(cfg["run_file"])
        qids     = sorted(run_data.keys())
        qid_to_text = {q["index"]: q["text"] for q in queries}

        lex_ranking = {qid: [pk for pk, _, _ in run_data[qid]] for qid in qids}
        mrr_lex = mrr_at_5(lex_ranking, gold)
        print(f"  A. Lexical baseline:               MRR@5 = {mrr_lex:.4f}")

        print(f"  Encoding {len(qids)} queries with BGE-M3 ...", end=" ", flush=True)
        t0    = time.time()
        texts = [qid_to_text.get(qid, "") for qid in qids]
        q_mat = bge_model.encode(
            texts,
            batch_size=args.batch_size,
            normalize_embeddings=False,  # do not normalise before PCA
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        print(f"done in {time.time()-t0:.1f}s")

        # Apply the pre-fitted PCA (1024→768) to the query matrix, then L2-normalise.
        print(f"  PCA transform {q_mat.shape[1]}→{sp2_dim} ...", end=" ", flush=True)
        q_mat_pca = pca.transform(q_mat).astype(np.float32)
        norms = np.linalg.norm(q_mat_pca, axis=1, keepdims=True)
        norms = np.where(norms == 0, 1.0, norms)
        q_mat_pca /= norms
        qid_to_vec = {qid: q_mat_pca[i] for i, qid in enumerate(qids)}
        print(f"done")

        # Cross-space cosine: PCA-reduced BGE-M3 query × SPECTER2 doc
        # Both L2-normalised; dot product is the cross-space proxy — exploratory only.
        qid_cosines = {}
        for qid in qids:
            q_vec = qid_to_vec[qid]
            cands = run_data[qid]
            qid_cosines[qid] = {
                pk: float(np.dot(q_vec, sp2_doc_embs[pk])) if pk in sp2_doc_embs else 0.0
                for pk, _, _ in cands
            }

        # Embedding reranking (cross-space)
        emb_ranking = {
            qid: [pk for pk, _, _ in sorted(run_data[qid], key=lambda x: -qid_cosines[qid].get(x[0], 0.0))]
            for qid in qids
        }
        mrr_emb = mrr_at_5(emb_ranking, gold)
        print(f"  B. Cross-space reranking:          MRR@5 = {mrr_emb:.4f}  (Δ={mrr_emb-mrr_lex:+.4f})")

        # Score fusion alpha sweep
        print(f"  C. Score fusion (BM25norm + cross-cosine):")
        best_mrr_f, best_alpha = 0.0, 0.0
        for alpha in ALPHAS:
            fusion_ranking = {}
            for qid in qids:
                cands     = run_data[qid]
                bm25_norm = normalize_scores([s for _, _, s in cands])
                cos_norm  = normalize_scores([qid_cosines[qid].get(pk, 0.0) for pk, _, _ in cands])
                combined  = [(1 - alpha) * b + alpha * c for b, c in zip(bm25_norm, cos_norm)]
                order     = sorted(range(len(cands)), key=lambda i: -combined[i])
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

    # Summary
    langs = list(all_results.keys())
    vals_fusion = [all_results[l]["fusion_best"] for l in langs]
    avg_fusion  = sum(vals_fusion) / len(langs)

    print(f"\n{'='*72}")
    print(f"  CROSS-SPACE SUMMARY  (SPECTER2 docs × BGE-M3 queries)  [EXPLORATORY]")
    print(f"{'='*72}")
    print(f"{'Method':<35}  {'EN':>7}  {'FR':>7}  {'DE':>7}  {'Avg':>7}")
    print("-" * 72)
    rows = [
        ("Lexical baseline",                  "lexical"),
        ("Cross-space reranking",             "emb_rerank"),
        ("Cross-space fusion (best α)",       "fusion_best"),
    ]
    for label, key in rows:
        vals = [all_results[l][key] for l in langs]
        avg  = sum(vals) / len(langs)
        print(f"{label:<35}  {vals[0]:>7.4f}  {vals[1]:>7.4f}  {vals[2]:>7.4f}  {avg:>7.4f}")

    print("-" * 72)
    print("Best fusion α: " + ", ".join(
        f"{l.upper()}={all_results[l]['fusion_alpha']:.1f}" for l in langs))

    print(f"\n--- Three-way comparison (best fusion) ---")
    print(f"{'Setup':<40}  {'EN':>7}  {'FR':>7}  {'DE':>7}  {'Avg':>7}")
    print("-" * 72)
    print(f"{'BGE-M3 symmetric (α=0.70)':<40}  "
          f"{REF['bge_fusion']['en']:>7.4f}  {REF['bge_fusion']['fr']:>7.4f}  "
          f"{REF['bge_fusion']['de']:>7.4f}  {REF['bge_fusion']['avg']:>7.4f}")
    print(f"{'SPECTER2 symmetric (α=0.60)':<40}  "
          f"{REF['sp2_fusion']['en']:>7.4f}  {REF['sp2_fusion']['fr']:>7.4f}  "
          f"{REF['sp2_fusion']['de']:>7.4f}  {REF['sp2_fusion']['avg']:>7.4f}")
    print(f"{'SPECTER2 docs × BGE-M3 queries [expl.]':<40}  "
          f"{vals_fusion[0]:>7.4f}  {vals_fusion[1]:>7.4f}  {vals_fusion[2]:>7.4f}  {avg_fusion:>7.4f}")


if __name__ == "__main__":
    main()
