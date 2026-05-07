#!/usr/bin/env python3
"""SPECTER2 vs BGE-M3 controlled comparison on the frozen lexical baseline.

Encodes documents with the SPECTER2 proximity adapter and claims with the
adhoc_query adapter — the asymmetric setup recommended by the SPECTER2 paper
for ad-hoc retrieval.  Everything else (run files, fusion formula, α sweep)
is identical to test_embedding_rerank_direct.py so results are directly
comparable.

Doc embeddings are cached in experiment/doc_embeddings_specter2.pkl.

Usage:
    python3 test_specter2_dev.py [--batch-size 32]
"""

import argparse
import json
import pickle
import time
from pathlib import Path

import numpy as np
import torch
from adapters import AutoAdapterModel
from transformers import AutoTokenizer

REPO_ROOT  = Path(__file__).resolve().parents[3]
RUNS_DIR   = REPO_ROOT / "runs"
EMB_FILE   = REPO_ROOT / "experiment" / "doc_embeddings_specter2.pkl"
COLLECTION = REPO_ROOT / "datasets/processed/documents/translated/collection_data_en_translated.json"

BASE_MODEL     = "allenai/specter2_base"
DOC_ADAPTER    = "allenai/specter2"           # proximity — paper representations
QUERY_ADAPTER  = "allenai/specter2_adhoc_query"  # ad-hoc query retrieval

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


# ---------------------------------------------------------------------------
# SPECTER2 encoding helpers
# ---------------------------------------------------------------------------

def load_model(adapter_name: str, device: str):
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = AutoAdapterModel.from_pretrained(BASE_MODEL)
    model.load_adapter(adapter_name, source="hf", load_as="specter2", set_active=True)
    model.eval()
    model.to(device)
    return tokenizer, model


@torch.no_grad()
def encode(texts: list, tokenizer, model, device: str, batch_size: int) -> np.ndarray:
    all_vecs = []
    for start in range(0, len(texts), batch_size):
        batch = texts[start:start + batch_size]
        inputs = tokenizer(
            batch,
            padding=True,
            truncation=True,
            max_length=512,
            return_tensors="pt",
        ).to(device)
        outputs = model(**inputs)
        # CLS token embedding (SPECTER convention)
        vecs = outputs.last_hidden_state[:, 0, :]
        # L2 normalise
        vecs = torch.nn.functional.normalize(vecs, dim=-1)
        all_vecs.append(vecs.cpu().numpy())
    return np.vstack(all_vecs)


# ---------------------------------------------------------------------------
# Run-file / evaluation helpers
# ---------------------------------------------------------------------------

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


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch-size", type=int, default=32)
    args = parser.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}")

    # --- Document embeddings (proximity adapter) ---
    if EMB_FILE.exists():
        print(f"Loading cached doc embeddings ({EMB_FILE.name}) ...", end=" ", flush=True)
        with open(EMB_FILE, "rb") as f:
            doc_embs = pickle.load(f)
        print(f"{len(doc_embs)} docs, dim={next(iter(doc_embs.values())).shape[0]}")
    else:
        print(f"Loading {BASE_MODEL} + {DOC_ADAPTER} for document encoding ...")
        tokenizer, model = load_model(DOC_ADAPTER, device)

        docs    = json.loads(COLLECTION.read_text())
        pubkeys = [d["pubkey"] for d in docs]
        # SPECTER2 doc format: title + SEP + abstract
        texts   = [
            d.get("title", "") + tokenizer.sep_token + d.get("abstract", "")
            for d in docs
        ]

        print(f"Encoding {len(texts)} documents (batch_size={args.batch_size}) ...")
        t0     = time.time()
        matrix = encode(texts, tokenizer, model, device, args.batch_size)
        print(f"Encoded in {time.time()-t0:.1f}s, shape={matrix.shape}")

        doc_embs = {pk: matrix[i] for i, pk in enumerate(pubkeys)}
        EMB_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(EMB_FILE, "wb") as f:
            pickle.dump(doc_embs, f, protocol=4)
        print(f"Saved → {EMB_FILE}  ({EMB_FILE.stat().st_size/1e6:.1f} MB)")

        del model  # free memory before loading query adapter

    # --- Query encoding (adhoc_query adapter) ---
    print(f"\nLoading {BASE_MODEL} + {QUERY_ADAPTER} for query encoding ...")
    q_tokenizer, q_model = load_model(QUERY_ADAPTER, device)

    all_results = {}

    for lang, cfg in LANG_TESTS.items():
        print(f"\n=== {lang.upper()} ===")
        queries  = json.loads(cfg["query_file"].read_text())
        gold_raw = json.loads(cfg["gold_file"].read_text())
        gold     = {c["index"]: c["pubkey"] for c in gold_raw}
        run_data = parse_run_file(cfg["run_file"])
        qids     = sorted(run_data.keys())
        qid_to_text = {q["index"]: q["text"] for q in queries}

        # Lexical baseline
        lex_ranking = {qid: [pk for pk, _, _ in run_data[qid]] for qid in qids}
        mrr_lex = mrr_at_5(lex_ranking, gold)
        print(f"  A. Lexical baseline:          MRR@5 = {mrr_lex:.4f}")

        # Encode queries
        print(f"  Encoding {len(qids)} queries ...", end=" ", flush=True)
        t0    = time.time()
        texts = [qid_to_text.get(qid, "") for qid in qids]
        q_mat = encode(texts, q_tokenizer, q_model, device, args.batch_size)
        qid_to_vec = {qid: q_mat[i] for i, qid in enumerate(qids)}
        print(f"done in {time.time()-t0:.1f}s")

        # Cosine = dot product (both L2-normalised)
        qid_cosines = {}
        for qid in qids:
            q_vec = qid_to_vec[qid]
            cands = run_data[qid]
            qid_cosines[qid] = {
                pk: float(np.dot(q_vec, doc_embs[pk])) if pk in doc_embs else 0.0
                for pk, _, _ in cands
            }

        # Embedding reranking
        emb_ranking = {
            qid: [pk for pk, _, _ in sorted(run_data[qid], key=lambda x: -qid_cosines[qid].get(x[0], 0.0))]
            for qid in qids
        }
        mrr_emb = mrr_at_5(emb_ranking, gold)
        print(f"  B. Embedding reranking:       MRR@5 = {mrr_emb:.4f}  (Δ={mrr_emb-mrr_lex:+.4f})")

        # Score fusion alpha sweep
        print(f"  C. Score fusion (BM25norm + cosine):")
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
    print(f"\n{'='*70}")
    print(f"  SPECTER2 RERANKING SUMMARY")
    print(f"{'='*70}")
    print(f"{'Method':<30}", end="")
    for l in langs:
        print(f"  {l.upper():>7}", end="")
    print(f"  {'Avg':>7}")
    print("-" * 70)

    bge_fusion = {"en": 0.5768, "fr": 0.5770, "de": 0.4331}  # BGE-M3 α=0.70 reference

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

    # Side-by-side with BGE-M3
    print(f"\n--- vs BGE-M3 fusion (α=0.70) ---")
    for l in langs:
        delta = all_results[l]["fusion_best"] - bge_fusion[l]
        print(f"  {l.upper()}: SPECTER2={all_results[l]['fusion_best']:.4f}  BGE-M3={bge_fusion[l]:.4f}  Δ={delta:+.4f}")
    sp2_avg = sum(all_results[l]["fusion_best"] for l in langs) / len(langs)
    bge_avg = sum(bge_fusion[l] for l in langs) / len(langs)
    print(f"  Avg: SPECTER2={sp2_avg:.4f}  BGE-M3={bge_avg:.4f}  Δ={sp2_avg-bge_avg:+.4f}")


if __name__ == "__main__":
    main()
