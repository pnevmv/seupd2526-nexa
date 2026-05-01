#!/usr/bin/env python3
"""Print the queries and documents that weren't matched by our system
"""

import argparse
import collections
import json
from pathlib import Path


def load_gold(claims_path: Path) -> dict[int, int]:
    with claims_path.open() as f:
        return {c["index"]: (c["pubkey"], c["text"]) for c in json.load(f)}

def load_docs(collection_path: Path) -> dict[int, any]:
    with collection_path.open() as f:
        return {c["pubkey"]: c for c in json.load(f)}


def first_gold_ranks(run_path: Path, gold: dict[int, int]) -> dict[int, int]:
    best: dict[int, int] = {}
    with run_path.open() as f:
        for line in f:
            qid_s, _, pk_s, rank_s, *_ = line.split()
            qid, pk, rank = int(qid_s), int(pk_s), int(rank_s)
            if gold.get(qid)[0] == pk and qid not in best:
                best[qid] = rank
    return best


def bucket(rank: int) -> str:
    if rank == 0:
        return "hit@1"
    if rank < 5:
        return "hit@5"
    if rank < 10:
        return "hit@10"
    if rank < 100:
        return "hit@100"
    return "hit@1000"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", nargs=1,
                        help="path to the TREC run file (default: runs/nexa-baseline.txt)")
    parser.add_argument("--claims", required=True,
                        help="path to the claims JSON with gold pubkeys")
    parser.add_argument("--collection", required=True,
                        help="path to the collection")
    parser.add_argument("-n", type=int, help="number of document to print")
    args = parser.parse_args()

    gold = load_gold(Path(args.claims))
    docs = load_docs(Path(args.collection))
    ranks = first_gold_ranks(Path(args.run[0]), gold)

    buckets: collections.Counter = collections.Counter()
    rr_sum = 0.0
    k = 0
    for qid in gold:
        r = ranks.get(qid)
        if r is None:
            buckets["missing"] += 1
            if args.n == None or k < args.n :
                query = gold.get(qid)
                doc = docs.get(query[0])
                print(f"This document wasn't found :")
                print(f"Query : {query[1]}\n")
                print(f"Expected Document :")
                for field, value in doc.items():
                    print(f"\t{field} : {value}")
                print(f"\n======================================\n")
                k += 1
        else:
            rr_sum += 1.0 / (r + 1)
            buckets[bucket(r)] += 1

    n = len(gold)
    print(f"run:    {args.run}")
    print(f"claims: {n}")
    for k in ("hit@1", "hit@5", "hit@10", "hit@100", "hit@1000", "missing"):
        c = buckets[k]
        print(f"  {k:10s} {c:6d}  ({100 * c / n:5.2f}%)")
    print(f"MRR (over all claims): {rr_sum / n:.4f}")


if __name__ == "__main__":
    main()
