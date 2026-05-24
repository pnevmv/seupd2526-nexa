"""
Generate publication-quality figures for the statistical analysis section of the HW2 report.

Produces:
  homework-2/figure/rr5_boxplot.pdf   – per-query RR@5 box plots, 3 languages
  homework-2/figure/rr5_tukey.pdf     – Tukey HSD significance heat-map
"""

import json
import itertools
from collections import defaultdict

import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
from scipy.stats import studentized_range, f

# ---------------------------------------------------------------------------
# paths
# ---------------------------------------------------------------------------
BASE = "/Users/nothingtodo/IdeaProjects/seupd2526-nexa"
OUT  = f"{BASE}/homework-2/figure"

RUN_FILES = {
    "en": {
        "Lexical":      f"{BASE}/runs/wider-en-dev-lexical.txt",
        "Hybrid":       f"{BASE}/runs/hybrid-en-dev-bge-fusion-10k-top100.txt",
        "CrossEncoder": f"{BASE}/runs/en_dev_ce_reranked.txt",
    },
    "fr": {
        "Lexical":      f"{BASE}/runs/wider-fr-dev-lexical.txt",
        "Hybrid":       f"{BASE}/runs/hybrid-fr-dev-bge-fusion-10k-top100.txt",
        "CrossEncoder": f"{BASE}/runs/fr_dev_ce_reranked.txt",
    },
    "de": {
        "Lexical":      f"{BASE}/runs/wider-de-dev-lexical.txt",
        "Hybrid":       f"{BASE}/runs/hybrid-de-dev-bge-fusion-10k-top100.txt",
        "CrossEncoder": f"{BASE}/runs/de_dev_ce_reranked.txt",
    },
}
DEV_FILES = {
    "en": f"{BASE}/datasets/en_dev.json",
    "fr": f"{BASE}/datasets/fr_dev.json",
    "de": f"{BASE}/datasets/de_dev.json",
}
CUTOFF = 5

# ---------------------------------------------------------------------------
# data helpers (same as statistical_analysis.py)
# ---------------------------------------------------------------------------
def load_qrels(path):
    with open(path) as fh:
        data = json.load(fh)
    return {item["index"]: item["pubkey"] for item in data}

def parse_run(path):
    rows = defaultdict(list)
    with open(path) as fh:
        for line in fh:
            p = line.split()
            if len(p) < 5:
                continue
            rows[int(p[0])].append((float(p[4]), int(p[2])))
    return {q: [d for _, d in sorted(hits, key=lambda x: -x[0])] for q, hits in rows.items()}

def rr_at_k(docs, rel, k=5):
    for pos, d in enumerate(docs[:k], 1):
        if d == rel:
            return 1.0 / pos
    return 0.0

def per_query_rr(run_path, qrels, k=5):
    ranked = parse_run(run_path)
    return {q: rr_at_k(ranked.get(q, []), rel, k) for q, rel in qrels.items()}

# ---------------------------------------------------------------------------
# collect data
# ---------------------------------------------------------------------------
SYSTEM_NAMES = ["Lexical", "Hybrid", "CrossEncoder"]
LANG_LABELS  = {"en": "English", "fr": "French", "de": "German"}

data = {}   # data[lang][system] = np.array of per-query RR@5
for lang in ["en", "fr", "de"]:
    qrels = load_qrels(DEV_FILES[lang])
    pq = {s: per_query_rr(RUN_FILES[lang][s], qrels) for s in SYSTEM_NAMES}
    common = sorted(set(pq["Lexical"]) & set(pq["Hybrid"]) & set(pq["CrossEncoder"]))
    data[lang] = {s: np.array([pq[s][q] for q in common]) for s in SYSTEM_NAMES}

# ---------------------------------------------------------------------------
# FIGURE 1 — box plots
# ---------------------------------------------------------------------------
COLORS = {
    "Lexical":      "#4878d0",
    "Hybrid":       "#ee854a",
    "CrossEncoder": "#6acc65",
}
DISPLAY = {
    "Lexical":      "Lexical\n(BM25)",
    "Hybrid":       "Hybrid\n(BM25+BGE-M3)",
    "CrossEncoder": "Cross-Encoder",
}

fig, axes = plt.subplots(1, 3, figsize=(9, 3.4), sharey=True)
fig.subplots_adjust(left=0.09, right=0.97, bottom=0.22, top=0.88, wspace=0.06)

for ax, lang in zip(axes, ["en", "fr", "de"]):
    arrays = [data[lang][s] for s in SYSTEM_NAMES]
    bp = ax.boxplot(
        arrays,
        patch_artist=True,
        widths=0.5,
        medianprops=dict(color="black", linewidth=1.5),
        flierprops=dict(marker="o", markersize=1.5, alpha=0.3, linestyle="none"),
        whiskerprops=dict(linewidth=0.9),
        capprops=dict(linewidth=0.9),
        showfliers=True,
    )
    for patch, name in zip(bp["boxes"], SYSTEM_NAMES):
        patch.set_facecolor(COLORS[name])
        patch.set_alpha(0.85)

    means = [np.mean(arr) for arr in arrays]
    ax.scatter(range(1, 4), means, marker="D", color="white",
               edgecolors="black", zorder=5, s=20, linewidths=0.8)

    n = len(arrays[0])
    ax.set_title(f"{LANG_LABELS[lang]}\n($n={n:,}$)", fontsize=9)
    ax.set_xticks(range(1, 4))
    ax.set_xticklabels([DISPLAY[s] for s in SYSTEM_NAMES], fontsize=7.5)
    ax.tick_params(axis="y", labelsize=8)
    ax.yaxis.grid(True, linestyle="--", linewidth=0.5, alpha=0.6)
    ax.set_axisbelow(True)
    if lang == "en":
        ax.set_ylabel("RR@5 per query", fontsize=8.5)

handles = [mpatches.Patch(facecolor=COLORS[s], alpha=0.85, label=DISPLAY[s].replace("\n", " "))
           for s in SYSTEM_NAMES]
fig.legend(handles=handles, loc="lower center", ncol=3, fontsize=8,
           bbox_to_anchor=(0.5, -0.01), frameon=False)

plt.savefig(f"{OUT}/rr5_boxplot.pdf", bbox_inches="tight", dpi=150)
plt.close()
print("Saved rr5_boxplot.pdf")

# ---------------------------------------------------------------------------
# FIGURE 2 — Tukey HSD significance matrix (heat-map style)
# ---------------------------------------------------------------------------
def two_way_anova_tukey(sdata, slabels):
    arr = np.array(sdata).T
    nt, ns = arr.shape
    gm = np.mean(arr)
    rm = np.mean(arr, axis=1)
    cm = np.mean(arr, axis=0)
    ss_t = np.sum((arr - gm) ** 2)
    ss_r = ns * np.sum((rm - gm) ** 2)
    ss_c = nt * np.sum((cm - gm) ** 2)
    ss_e = ss_t - ss_r - ss_c
    df_e = (nt - 1) * (ns - 1)
    ms_c = ss_c / (ns - 1)
    ms_e = ss_e / df_e
    f_val = ms_c / ms_e
    p_val = 1 - f.cdf(f_val, ns - 1, df_e)
    tukey = []
    for i, j in itertools.combinations(range(ns), 2):
        diff = cm[i] - cm[j]
        q = abs(diff) / np.sqrt(ms_e / nt)
        p = 1 - studentized_range.cdf(q, ns, df_e)
        tukey.append({"i": i, "j": j, "diff": diff, "p": p})
    return {"f": f_val, "p": p_val, "means": cm, "tukey": tukey, "ms_e": ms_e, "df_e": df_e, "nt": nt}

langs    = ["en", "fr", "de"]
lang_lbl = ["English", "French", "German"]
n_langs  = len(langs)
n_sys    = len(SYSTEM_NAMES)
disp_sys = ["Lexical", "Hybrid", "CE"]

# build p-value matrices and mean-diff matrices per language
pmat   = {}
dmat   = {}
fstats = {}
for lang in langs:
    sdata  = [data[lang][s] for s in SYSTEM_NAMES]
    res    = two_way_anova_tukey(sdata, SYSTEM_NAMES)
    pm     = np.full((n_sys, n_sys), np.nan)
    dm     = np.full((n_sys, n_sys), np.nan)
    for t in res["tukey"]:
        pm[t["i"], t["j"]] = t["p"]
        pm[t["j"], t["i"]] = t["p"]
        dm[t["i"], t["j"]] = t["diff"]
        dm[t["j"], t["i"]] = -t["diff"]
    pmat[lang]   = pm
    dmat[lang]   = dm
    fstats[lang] = res

fig, axes = plt.subplots(1, 3, figsize=(9, 3.0))
fig.subplots_adjust(left=0.07, right=0.96, bottom=0.18, top=0.82, wspace=0.35)

# colour scale: green = significant (low p), red = not significant (high p)
from matplotlib.colors import LinearSegmentedColormap
cmap = LinearSegmentedColormap.from_list("sig", ["#2ca02c", "#d62728"], N=256)

for ax, lang, ll in zip(axes, langs, lang_lbl):
    pm  = pmat[lang]
    dm  = dmat[lang]
    res = fstats[lang]

    masked = np.where(np.isnan(pm), 0.5, pm)
    im = ax.imshow(masked, cmap=cmap, vmin=0, vmax=1, aspect="equal")

    for i in range(n_sys):
        for j in range(n_sys):
            if i == j:
                ax.text(j, i, disp_sys[i], ha="center", va="center",
                        fontsize=8.5, fontweight="bold", color="white")
            elif not np.isnan(pm[i, j]):
                p = pm[i, j]
                d = dm[i, j]
                star = "***" if p < 0.001 else ("**" if p < 0.01 else ("*" if p < 0.05 else "n.s."))
                txt  = f"{d:+.3f}\n{star}"
                col  = "white" if p < 0.25 else "black"
                ax.text(j, i, txt, ha="center", va="center", fontsize=7.5, color=col)

    ax.set_xticks(range(n_sys))
    ax.set_yticks(range(n_sys))
    ax.set_xticklabels(disp_sys, fontsize=8)
    ax.set_yticklabels(disp_sys, fontsize=8)
    ax.tick_params(length=0)
    anova_p = res["p"]
    sig_str  = f"$p < 0.001$" if anova_p < 0.001 else f"$p = {anova_p:.3f}$"
    ax.set_title(f"{ll}\n$F={res['f']:.2f}$, {sig_str}", fontsize=8.5)

    for spine in ax.spines.values():
        spine.set_visible(False)

# shared colorbar
cbar_ax = fig.add_axes([0.97, 0.22, 0.015, 0.55])
sm = plt.cm.ScalarMappable(cmap=cmap, norm=plt.Normalize(0, 1))
sm.set_array([])
cb = fig.colorbar(sm, cax=cbar_ax)
cb.set_label("Tukey $p$-value", fontsize=7.5)
cb.set_ticks([0, 0.05, 0.25, 0.5, 1.0])
cb.ax.tick_params(labelsize=7)

fig.text(0.5, 0.01,
         "Upper-left triangle: mean RR@5 difference (row$-$col).   "
         "Green = significant ($p<0.05$), red = not significant.",
         ha="center", fontsize=7.5, style="italic")

plt.savefig(f"{OUT}/rr5_tukey.pdf", bbox_inches="tight", dpi=150)
plt.close()
print("Saved rr5_tukey.pdf")
