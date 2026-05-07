#!/usr/bin/env python3
"""Precompute BGE-M3 (multilingual) embeddings for all 10k documents.

Loads BGE-M3 directly (bypasses the HTTP server) for fast batch encoding.

Reads: datasets/processed/documents/translated/collection_data_en_translated.json
Writes: experiment/doc_embeddings.pkl  — dict: pubkey(int) -> np.ndarray (1024-dim)

Usage:
    python3 precompute_doc_embeddings.py [--batch-size 256]
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
COLLECTION = REPO_ROOT / "datasets/processed/documents/translated/collection_data_en_translated.json"
OUT_FILE   = REPO_ROOT / "experiment/doc_embeddings.pkl"
MODEL_NAME = "BAAI/bge-m3"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch-size", type=int, default=256,
                        help="Encoding batch size (larger = faster, more VRAM)")
    args = parser.parse_args()

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    print(f"Device: {device}")
    print(f"Loading {MODEL_NAME} ...", end=" ", flush=True)
    t0 = time.time()
    model = SentenceTransformer(MODEL_NAME, device=device)
    print(f"done in {time.time()-t0:.1f}s")

    docs = json.loads(COLLECTION.read_text())
    print(f"Loaded {len(docs)} documents")

    pubkeys = [d["pubkey"] for d in docs]
    texts   = [f"{d.get('title','')} {d.get('abstract','')}".strip() for d in docs]

    print(f"Encoding {len(texts)} docs (batch_size={args.batch_size}) ...")
    t0 = time.time()
    embeddings_matrix = model.encode(
        texts,
        batch_size=args.batch_size,
        show_progress_bar=True,
        normalize_embeddings=True,
        convert_to_numpy=True,
    )
    elapsed = time.time() - t0
    print(f"Encoded in {elapsed:.1f}s  ({elapsed/len(texts)*1000:.1f} ms/doc)")
    print(f"Matrix shape: {embeddings_matrix.shape}")

    emb_dict = {pk: embeddings_matrix[i] for i, pk in enumerate(pubkeys)}

    OUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT_FILE, "wb") as f:
        pickle.dump(emb_dict, f, protocol=4)
    size_mb = OUT_FILE.stat().st_size / 1e6
    print(f"Saved → {OUT_FILE}  ({size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
