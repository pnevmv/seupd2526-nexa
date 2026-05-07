#!/usr/bin/env python3
"""Generate hybrid fusion run files (top-100) for EN/FR/DE train sets.

Pipeline:
  1. Patch config.yml, run Java Searcher → BM25 top-1000 per query
  2. Encode queries with BGE-M3, compute cosine over all 10k docs
  3. Fuse: (1-alpha)*BM25_norm + alpha*cosine  (BM25=0 for unseen docs)
  4. Write top-100 results per query to runs/nexa-{lang}-train-hybrid-10k.txt

Usage:
    python3 generate_train_runs_10k.py [--alpha 0.80] [--top-k 100] [--batch-size 32]
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
BM25_POOL  = 1000

LANG_CONFIGS = {
    "en": {
        "topics":     "datasets/en_train.json",
        "query_file": REPO_ROOT / "datasets/en_train.json",
        "bm25_run_id": "nexa-en-train-bm25-wide",
        "out_file":   REPO_ROOT / "runs/nexa-en-train-hybrid-10k.txt",
        "run_tag":    "nexa-en-train-hybrid-10k",
    },
    "fr": {
        "topics":     "datasets/processed/queries/translated/fr_train_en_translated.json",
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/fr_train_en_translated.json",
        "bm25_run_id": "nexa-fr-train-bm25-wide",
        "out_file":   REPO_ROOT / "runs/nexa-fr-train-hybrid-10k.txt",
        "run_tag":    "nexa-fr-train-hybrid-10k",
    },
    "de": {
        "topics":     "datasets/processed/queries/translated/de_train_en_translated.json",
        "query_file": REPO_ROOT / "datasets/processed/queries/translated/de_train_en_translated.json",
        "bm25_run_id": "nexa-de-train-bm25-wide",
        "out_file":   REPO_ROOT / "runs/nexa-de-train-hybrid-10k.txt",
        "run_tag":    "nexa-de-train-hybrid-10k",
    },
}


def run_bm25(cfg: dict) -> Path:
    """Patch config, run Java Searcher, return path to BM25 run file."""
    original = CONFIG.read_text()
    lines = []
    for line in original.splitlines():
        if line.startswith("topics:"):
            lines.append(f"topics: {cfg['topics']}")
        elif line.startswith("runID:"):
            lines.append(f"runID: {cfg['bm25_run_id']}")
        elif line.startswith("runPath:"):
            lines.append("runPath: runs")
        elif line.startswith("maxDocsRetrieved:"):
            lines.append(f"maxDocsRetrieved: {BM25_POOL}")
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
            print(f"    FAILED (exit {proc.returncode})")
            print(proc.stderr[:1000])
            return None
        run_file = REPO_ROOT / "runs" / f"{cfg['bm25_run_id']}.txt"
        lines_count = sum(1 for _ in open(run_file))
        print(f"    BM25 done — {lines_count} lines  [{elapsed:.0f}s]")
        return run_file
    finally:
        CONFIG.write_text(original)


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
    parser.add_argument("--top-k",      type=int,   default=100)
    parser.add_argument("--batch-size", type=int,   default=32)
    args = parser.parse_args()

    if not JAR.exists():
        print("ERROR: JAR not found. Run: cd code && mvn package -DskipTests")
        return

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}  alpha={args.alpha}  top-k={args.top_k}")
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
        print(f"\n=== {lang.upper()} train ===")

        print(f"  Running BM25 top-{BM25_POOL} ...")
        bm25_run_file = run_bm25(cfg)
        if bm25_run_file is None:
            continue

        bm25_map = parse_run_file(bm25_run_file)
        queries  = json.loads(cfg["query_file"].read_text())
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

        print(f"  Computing cosine + fusing → top-{args.top_k} ...", end=" ", flush=True)
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
                order    = np.argsort(-combined)[:args.top_k]

                tag = cfg["run_tag"]
                for rank, j in enumerate(order):
                    out.write(f"{qid} Q0 {all_pks[j]} {rank} {combined[j]:.6f} {tag}\n")
                    written += 1

        print(f"done in {time.time()-t0:.1f}s")
        print(f"  Written {written} lines → {out_path.name}")

    print("\nDone.")


if __name__ == "__main__":
    main()
