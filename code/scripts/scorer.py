#!/usr/bin/env python3
"""Evaluate a TREC run file using the official scorer of CheckThat
Create a tsv file in the same folder as the run.
"""

import argparse
import csv
import collections
import json
import numpy as np
from pathlib import Path
from typing import List


def load_gold(claims_path: Path) -> dict[int, int]:
    with claims_path.open() as f:
        return {c["index"]: c["pubkey"] for c in json.load(f)}


def ranksAt5(run_path: Path) -> dict[int, List[int]]:
    ranks: dict[int, List[int]] = collections.defaultdict(list)
    with run_path.open() as f:
        for line in f:
            qid_s, _, pk_s, rank_s, *_ = line.split()
            qid, pk, rank = int(qid_s), int(pk_s), int(rank_s)
            if rank <= 4:
                ranks[qid].append(pk)
    return ranks

def scorer(ranks: dict[int, List[int]], labels: dict[int, int]) -> float :
    mrr_scores = []
    for (qid, preds) in ranks.items() :
        assert len(preds) == 5, "exactly 5 predictions per query should be provided"
        label = labels.get(qid)
        if label in preds:
            mrr_scores.append(1 / (preds.index(label) + 1))
        else:
            mrr_scores.append(0)

    return float(np.mean(mrr_scores))

def save_predictions_to_tsv(predictions: dict[int, List[int]], path:Path, lang: str):
    """
    Saves a dictionary of predictions to a TSV file.
    """
    filename = f"{path}/predictions_{lang}.tsv"

    # Open the file with newline='' to prevent extra empty lines in Windows
    with open(filename, mode='w', newline='', encoding='utf-8') as f:
        writer = csv.writer(f, delimiter='\t')

        # Write the header row
        writer.writerow(["index", "preds"])

        # Write the data rows
        for post_index, preds in predictions.items():
            # Slice [:5] to guarantee we only output the top 5 predictions
            top_5_preds = preds[:5]

            # str(top_5_preds) automatically formats it as "[pred1, pred2, ...]"
            writer.writerow([post_index, str(top_5_preds)])

    print(f"{filename} has been created")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", nargs="?", default="runs/nexa-baseline.txt",
                        help="path to the TREC run file (default: runs/nexa-baseline.txt)")
    parser.add_argument("--claims", default="code/datasets/en_train.json",
                        help="path to the claims JSON with gold pubkeys")
    args = parser.parse_args()

    gold = load_gold(Path(args.claims))
    ranks = ranksAt5(Path(args.run))


    n = len(gold)
    score = scorer(ranks, gold)
    print(f"run:    {args.run}")
    print(f"claims: {n}")
    print(f"MRR@5: {score:.4f}")

    # Create tsv
    save_predictions_to_tsv(ranks, Path(args.run).parent, Path(args.run).stem)


if __name__ == "__main__":
    main()
