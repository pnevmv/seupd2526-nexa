"""
Two-way ANOVA + Tukey HSD analysis comparing three IR systems on the dev set.

Systems compared:
  1. Lexical (BM25)      – wider-{lang}-dev-lexical.txt
  2. Hybrid (BM25+BGE-M3) – hybrid-{lang}-dev-bge-fusion-10k-top100.txt
  3. Cross-encoder        – {lang}_dev_ce_reranked.txt

Measure: RR@5 (Reciprocal Rank at cutoff 5), computed directly from run files
         and ground-truth pubkeys in the dev JSON files.
"""

import json
import itertools
from collections import defaultdict

import numpy as np
from scipy.stats import studentized_range, f


# ---------------------------------------------------------------------------
# paths
# ---------------------------------------------------------------------------
BASE = "/Users/nothingtodo/IdeaProjects/seupd2526-nexa"

RUN_FILES = {
    "en": {
        "Lexical":       f"{BASE}/runs/wider-en-dev-lexical.txt",
        "Hybrid":        f"{BASE}/runs/hybrid-en-dev-bge-fusion-10k-top100.txt",
        "CrossEncoder":  f"{BASE}/runs/en_dev_ce_reranked.txt",
    },
    "fr": {
        "Lexical":       f"{BASE}/runs/wider-fr-dev-lexical.txt",
        "Hybrid":        f"{BASE}/runs/hybrid-fr-dev-bge-fusion-10k-top100.txt",
        "CrossEncoder":  f"{BASE}/runs/fr_dev_ce_reranked.txt",
    },
    "de": {
        "Lexical":       f"{BASE}/runs/wider-de-dev-lexical.txt",
        "Hybrid":        f"{BASE}/runs/hybrid-de-dev-bge-fusion-10k-top100.txt",
        "CrossEncoder":  f"{BASE}/runs/de_dev_ce_reranked.txt",
    },
}

DEV_FILES = {
    "en": f"{BASE}/datasets/en_dev.json",
    "fr": f"{BASE}/datasets/fr_dev.json",
    "de": f"{BASE}/datasets/de_dev.json",
}

CUTOFF = 5


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def load_qrels(dev_path):
    """Return dict: query_id (int) -> relevant_doc_id (int)."""
    with open(dev_path) as f:
        data = json.load(f)
    return {item["index"]: item["pubkey"] for item in data}


def parse_run(run_path):
    """
    Parse a TREC-format run file.
    Returns dict: query_id (int) -> list of doc_ids in ranked order (score desc).
    The rank column is ignored; we sort by score to be robust to indexing differences.
    """
    rows = defaultdict(list)
    with open(run_path) as f:
        for line in f:
            parts = line.split()
            if len(parts) < 5:
                continue
            qid = int(parts[0])
            doc_id = int(parts[2])
            score = float(parts[4])
            rows[qid].append((score, doc_id))
    ranked = {}
    for qid, hits in rows.items():
        hits.sort(key=lambda x: -x[0])
        ranked[qid] = [doc_id for _, doc_id in hits]
    return ranked


def rr_at_k(ranked_docs, relevant_doc, k=5):
    """Reciprocal rank at cutoff k. Returns 0.0 if not in top-k."""
    for pos, doc in enumerate(ranked_docs[:k], start=1):
        if doc == relevant_doc:
            return 1.0 / pos
    return 0.0


def compute_per_query_rr(run_path, qrels, cutoff=5):
    """Return dict: query_id -> RR@cutoff."""
    ranked = parse_run(run_path)
    scores = {}
    for qid, rel_doc in qrels.items():
        docs = ranked.get(qid, [])
        scores[qid] = rr_at_k(docs, rel_doc, cutoff)
    return scores


# ---------------------------------------------------------------------------
# two-way ANOVA + Tukey HSD (from HypothesisTestingNB.ipynb cell 103)
# ---------------------------------------------------------------------------
def two_way_anova_tukey(systems_data, systems_labels):
    data = np.array(systems_data).T  # shape: (topics, systems)
    n_topics, n_systems = data.shape

    grand_mean = np.mean(data)
    row_means = np.mean(data, axis=1)
    col_means = np.mean(data, axis=0)

    ss_total = np.sum((data - grand_mean) ** 2)
    ss_rows = n_systems * np.sum((row_means - grand_mean) ** 2)
    ss_cols = n_topics * np.sum((col_means - grand_mean) ** 2)
    ss_error = ss_total - ss_rows - ss_cols

    df_total = n_topics * n_systems - 1
    df_rows = n_topics - 1
    df_cols = n_systems - 1
    df_error = df_total - df_rows - df_cols

    ms_cols = ss_cols / df_cols
    ms_error = ss_error / df_error

    f_val = ms_cols / ms_error
    p_val = 1 - f.cdf(f_val, df_cols, df_error)

    pairs = list(itertools.combinations(range(n_systems), 2))
    tukey_results = []
    for i, j in pairs:
        mean_diff = np.abs(col_means[i] - col_means[j])
        se = np.sqrt(ms_error / n_topics)
        q_stat = mean_diff / se
        p_tukey = 1 - studentized_range.cdf(q_stat, n_systems, df_error)
        tukey_results.append({
            "pair": (systems_labels[i], systems_labels[j]),
            "mean_i": col_means[i],
            "mean_j": col_means[j],
            "mean_diff": col_means[i] - col_means[j],
            "q_stat": q_stat,
            "p_value": p_tukey,
            "significant": p_tukey < 0.05,
        })

    return {
        "n_topics": n_topics,
        "n_systems": n_systems,
        "col_means": dict(zip(systems_labels, col_means)),
        "ss_cols": ss_cols, "ss_rows": ss_rows, "ss_error": ss_error,
        "df_cols": df_cols, "df_rows": df_rows, "df_error": df_error,
        "ms_cols": ms_cols, "ms_error": ms_error,
        "f_val": f_val, "p_val": p_val,
        "tukey": tukey_results,
    }


# ---------------------------------------------------------------------------
# main analysis
# ---------------------------------------------------------------------------
def run_analysis_for_language(lang):
    print(f"\n{'='*60}")
    print(f"  Language: {lang.upper()}")
    print(f"{'='*60}")

    qrels = load_qrels(DEV_FILES[lang])
    system_names = ["Lexical", "Hybrid", "CrossEncoder"]

    per_query = {}
    for name in system_names:
        per_query[name] = compute_per_query_rr(RUN_FILES[lang][name], qrels, CUTOFF)
        print(f"  {name}: {len(per_query[name])} queries evaluated")

    # align to queries present in all three runs
    common_qids = sorted(
        set(per_query["Lexical"]) & set(per_query["Hybrid"]) & set(per_query["CrossEncoder"])
    )
    print(f"  Queries in common across all 3 systems: {len(common_qids)}")

    systems_data = [
        np.array([per_query[name][q] for q in common_qids])
        for name in system_names
    ]

    # per-system mean RR@5
    print(f"\n  Per-system mean RR@{CUTOFF}:")
    for name, arr in zip(system_names, systems_data):
        print(f"    {name:15s}: {np.mean(arr):.4f}  (std={np.std(arr):.4f})")

    # two-way ANOVA
    result = two_way_anova_tukey(systems_data, system_names)

    print(f"\n  Two-way ANOVA results:")
    print(f"    Topics (rows) block: SS={result['ss_rows']:.4f}, df={result['df_rows']}")
    print(f"    Systems (cols):      SS={result['ss_cols']:.4f}, df={result['df_cols']}, "
          f"MS={result['ms_cols']:.4f}")
    print(f"    Error:               SS={result['ss_error']:.4f}, df={result['df_error']}, "
          f"MS={result['ms_error']:.4f}")
    print(f"    F-statistic: {result['f_val']:.4f}")
    print(f"    p-value:     {result['p_val']:.6f}")
    sig = "REJECT H0 (p < 0.05)" if result["p_val"] < 0.05 else "FAIL TO REJECT H0 (p >= 0.05)"
    print(f"    Decision:    {sig}")

    print(f"\n  Tukey HSD pairwise comparisons (alpha=0.05):")
    print(f"    {'Pair':<35} {'Diff':>8} {'q-stat':>8} {'p-value':>10} {'Sig?':>6}")
    print(f"    {'-'*70}")
    for r in result["tukey"]:
        a, b = r["pair"]
        print(f"    {a:15s} vs {b:15s}  {r['mean_diff']:+.4f}   {r['q_stat']:7.4f}   "
              f"{r['p_value']:9.6f}   {'YES' if r['significant'] else 'no':>4}")

    return result, common_qids, systems_data


if __name__ == "__main__":
    print(f"Statistical analysis: Two-way ANOVA + Tukey HSD")
    print(f"Measure: RR@{CUTOFF} per query")
    print(f"Systems: Lexical (BM25) | Hybrid (BM25+BGE-M3) | Cross-Encoder")

    results_by_lang = {}
    for lang in ["en", "fr", "de"]:
        res, qids, sdata = run_analysis_for_language(lang)
        results_by_lang[lang] = (res, qids, sdata)

    # combined analysis across all languages
    print(f"\n{'='*60}")
    print(f"  COMBINED (all languages pooled)")
    print(f"{'='*60}")

    system_names = ["Lexical", "Hybrid", "CrossEncoder"]
    combined = {name: [] for name in system_names}
    for lang in ["en", "fr", "de"]:
        _, qids, sdata = results_by_lang[lang]
        for name, arr in zip(system_names, sdata):
            combined[name].extend(arr.tolist())

    combined_data = [np.array(combined[name]) for name in system_names]
    print(f"  Total queries (pooled): {len(combined_data[0])}")
    print(f"\n  Per-system mean RR@{CUTOFF} (combined):")
    for name, arr in zip(system_names, combined_data):
        print(f"    {name:15s}: {np.mean(arr):.4f}")

    result_combined = two_way_anova_tukey(combined_data, system_names)
    print(f"\n  Two-way ANOVA (combined):")
    print(f"    F={result_combined['f_val']:.4f}, p={result_combined['p_val']:.6f}")
    sig = "REJECT H0" if result_combined["p_val"] < 0.05 else "FAIL TO REJECT H0"
    print(f"    Decision: {sig}")
    print(f"\n  Tukey HSD (combined):")
    print(f"    {'Pair':<35} {'Diff':>8} {'p-value':>10} {'Sig?':>6}")
    for r in result_combined["tukey"]:
        a, b = r["pair"]
        print(f"    {a:15s} vs {b:15s}  {r['mean_diff']:+.4f}   {r['p_value']:9.6f}   "
              f"{'YES' if r['significant'] else 'no':>4}")
