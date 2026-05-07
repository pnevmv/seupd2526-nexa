#!/usr/bin/env python3
"""Zero-shot cross-encoder reranking on top of the frozen BM25+BGE-M3 hybrid.

Pipeline: hybrid fusion run → top-k shortlist → cross-encoder rerank → MRR@5

Model: cross-encoder/ms-marco-MiniLM-L-6-v2  (same as reranking_server.py)
Input pairs: (claim_en, title + " " + abstract)
Shortlist sizes tested: 10, 20, 50

Usage:
    python3 test_crossencoder_dev.py [--batch-size 64]
"""

import argparse
import json
import time
from pathlib import Path

import torch
from sentence_transformers import CrossEncoder

REPO_ROOT  = Path(__file__).resolve().parents[3]
RUNS_DIR   = REPO_ROOT / "runs"
COLLECTION = REPO_ROOT / "datasets/processed/documents/translated/collection_data_en_translated.json"
MODEL_NAME = "cross-encoder/ms-marco-MiniLM-L-6-v2"

LANG_TESTS = {
    "en": {
        "query_file":  REPO_ROOT / "datasets/en_dev.json",
        "gold_file":   REPO_ROOT / "datasets/en_dev.json",
        "hybrid_run":  RUNS_DIR  / "hybrid-en-dev-bge-fusion.txt",
        "lexical_run": RUNS_DIR  / "abl-Full_best_reference-en.txt",
    },
    "fr": {
        "query_file":  REPO_ROOT / "datasets/processed/queries/translated/fr_dev_en_translated.json",
        "gold_file":   REPO_ROOT / "datasets/fr_dev.json",
        "hybrid_run":  RUNS_DIR  / "hybrid-fr-dev-bge-fusion.txt",
        "lexical_run": RUNS_DIR  / "abl-Full_best_reference-fr.txt",
    },
    "de": {
        "query_file":  REPO_ROOT / "datasets/processed/queries/translated/de_dev_en_translated.json",
        "gold_file":   REPO_ROOT / "datasets/de_dev.json",
        "hybrid_run":  RUNS_DIR  / "hybrid-de-dev-bge-fusion.txt",
        "lexical_run": RUNS_DIR  / "abl-Full_best_reference-de.txt",
    },
}

SHORTLIST_SIZES = [10, 20, 50]

# Reference MRR@5 from previous experiments
REF = {
    "lexical": {"en": 0.5172, "fr": 0.5145, "de": 0.3841, "avg": 0.4719},
    "hybrid":  {"en": 0.5768, "fr": 0.5770, "de": 0.4331, "avg": 0.5290},
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


def mrr_at_5(ranking: dict, gold: dict) -> float:
    rr = 0.0
    for qid, pubkey in gold.items():
        cands = ranking.get(qid, [])
        if pubkey in cands[:5]:
            rr += 1.0 / (cands.index(pubkey) + 1)
    return rr / len(gold)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--shortlist", type=int, nargs="+", default=SHORTLIST_SIZES,
                        help="Shortlist sizes to test (default: 10 20 50)")
    args = parser.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}")
    print(f"Loading {MODEL_NAME} ...", end=" ", flush=True)
    t0 = time.time()
    model = CrossEncoder(MODEL_NAME, device=device)
    print(f"done in {time.time()-t0:.1f}s")

    print(f"Loading collection ...", end=" ", flush=True)
    docs_raw = json.loads(COLLECTION.read_text())
    pub_texts = {
        d["pubkey"]: f"{d.get('title', '')} {d.get('abstract', '')}".strip()
        for d in docs_raw
    }
    print(f"{len(pub_texts)} docs")

    # Results: lang -> shortlist_k -> MRR@5
    all_results: dict[str, dict[int, float]] = {}
    all_times:   dict[str, dict[int, float]] = {}

    for lang, cfg in LANG_TESTS.items():
        print(f"\n=== {lang.upper()} ===")
        queries  = json.loads(cfg["query_file"].read_text())
        gold_raw = json.loads(cfg["gold_file"].read_text())
        gold     = {c["index"]: c["pubkey"] for c in gold_raw}
        hybrid   = parse_run_file(cfg["hybrid_run"])
        qids     = sorted(hybrid.keys())
        qid_to_text = {q["index"]: q["text"] for q in queries}

        # Hybrid baseline (already computed, just verify)
        hybrid_ranking = {qid: [pk for pk, _, _ in hybrid[qid]] for qid in qids}
        mrr_hybrid = mrr_at_5(hybrid_ranking, gold)
        print(f"  Hybrid baseline (verify):  MRR@5 = {mrr_hybrid:.4f}")

        all_results[lang] = {}
        all_times[lang]   = {}

        for k in args.shortlist:
            t0 = time.time()
            ce_ranking = {}

            # Build all pairs for this language at once, then batch-predict
            all_pairs   = []
            pair_index  = []  # (qid, position_in_pairs)

            for qid in qids:
                q_text = qid_to_text.get(qid, "")
                cands  = hybrid[qid][:k]
                start  = len(all_pairs)
                for pk, _, _ in cands:
                    all_pairs.append([q_text, pub_texts.get(pk, "")])
                pair_index.append((qid, start, len(cands)))

            # Single batched predict over all queries × k pairs
            scores_flat = model.predict(
                all_pairs,
                batch_size=args.batch_size,
                show_progress_bar=False,
            )

            # Rebuild per-query rankings
            ptr = 0
            for qid, start, n in pair_index:
                ce_scores = scores_flat[start:start + n]
                cands = hybrid[qid][:k]
                order = sorted(range(n), key=lambda i: -ce_scores[i])
                ce_ranking[qid] = [cands[i][0] for i in order]
            ptr = 0

            elapsed = time.time() - t0
            mrr_ce  = mrr_at_5(ce_ranking, gold)
            all_results[lang][k] = mrr_ce
            all_times[lang][k]   = elapsed

            delta = mrr_ce - mrr_hybrid
            print(f"  CE rerank top-{k:2d}:  MRR@5 = {mrr_ce:.4f}  "
                  f"(Δ vs hybrid={delta:+.4f})  [{elapsed:.1f}s]")

    # Summary table
    langs = list(all_results.keys())
    print(f"\n{'='*76}")
    print(f"  CROSS-ENCODER RERANKING SUMMARY  ({MODEL_NAME})")
    print(f"{'='*76}")
    print(f"{'Method':<28}", end="")
    for l in langs:
        print(f"  {l.upper():>7}", end="")
    print(f"  {'Avg':>7}  {'Time':>8}")
    print("-" * 76)

    # Reference rows
    for label, ref in [("Lexical baseline", "lexical"), ("Hybrid BM25+BGE-M3", "hybrid")]:
        vals = [REF[ref][l] for l in langs]
        avg  = REF[ref]["avg"]
        print(f"{label:<28}", end="")
        for v in vals:
            print(f"  {v:>7.4f}", end="")
        print(f"  {avg:>7.4f}  {'':>8}")

    for k in args.shortlist:
        vals    = [all_results[l][k] for l in langs]
        avg     = sum(vals) / len(vals)
        t_total = sum(all_times[l][k] for l in langs)
        print(f"{'CE rerank top-'+str(k):<28}", end="")
        for v in vals:
            print(f"  {v:>7.4f}", end="")
        print(f"  {avg:>7.4f}  {t_total:>7.1f}s")

    print("-" * 76)
    print("\nDelta vs hybrid baseline:")
    for k in args.shortlist:
        vals  = [all_results[l][k] for l in langs]
        avg   = sum(vals) / len(langs)
        delta = avg - REF["hybrid"]["avg"]
        print(f"  top-{k:2d}: Δ avg = {delta:+.4f}  "
              + "  ".join(f"{l.upper()}={all_results[l][k]-REF['hybrid'][l]:+.4f}" for l in langs))


if __name__ == "__main__":
    main()
