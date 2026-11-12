#!/usr/bin/env python3
"""Builds the report figures from bench/results/*.csv.

Usage:  python bench/plot.py [results_dir] [figures_dir]
Requires matplotlib and pandas.
"""

import json
import os
import sys

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

RES = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "results")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "..",
                                                          "report", "figures")
os.makedirs(OUT, exist_ok=True)

# Categorical slots in fixed order (validated: CVD-safe adjacent pairs); markers and line
# styles are the secondary encoding so the figures also work in black-and-white print.
C = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"]
INK, MUTED, GRID = "#1d1f24", "#6b6f78", "#e3e1da"
IMPL = {"partitioned": ("неблокуюча (partitioned)", C[0], "o", "-"),
        "striped": ("блокувальна (striped)", C[1], "s", "--")}

plt.rcParams.update({
    "font.family": "DejaVu Sans", "font.size": 10, "axes.edgecolor": MUTED,
    "axes.labelcolor": INK, "xtick.color": MUTED, "ytick.color": MUTED,
    "axes.spines.top": False, "axes.spines.right": False, "axes.grid": True,
    "grid.color": GRID, "grid.linewidth": 0.8, "lines.linewidth": 2, "lines.markersize": 6,
    "legend.frameon": False, "figure.dpi": 150, "savefig.bbox": "tight",
})


def save(fig, name):
    fig.savefig(os.path.join(OUT, name))
    plt.close(fig)
    print("figure", name)


def amdahl_fit(p, s):
    """Least-squares serial fraction f for S(p) = 1 / (f + (1 - f) / p)."""
    best = min(np.linspace(0, 0.5, 5001),
               key=lambda f: np.sum((s - 1 / (f + (1 - f) / p)) ** 2))
    return float(best)


summary = {}

# ------------------------------------------------------------------ index build vs threads
idx = pd.read_csv(os.path.join(RES, "index.csv"))
if "mapper" in idx.columns:
    idx = idx[idx.mapper == "arena"]
agg = idx.groupby(["impl", "threads"]).time_ms.agg(["median", "min", "max"]).reset_index()

fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.8))
for impl, (label, color, marker, ls) in IMPL.items():
    g = agg[agg.impl == impl].sort_values("threads")
    p = g.threads.to_numpy()
    med = g["median"].to_numpy() / 1000
    a1.errorbar(p, med, yerr=[med - g["min"] / 1000, g["max"] / 1000 - med], color=color,
                marker=marker, ls=ls, capsize=3, label=label)
    s = med[0] / med
    a2.plot(p, s, color=color, marker=marker, ls=ls, label=label)
    fit_mask = p <= 12
    f = amdahl_fit(p[fit_mask], s[fit_mask])
    kf = [(1 / si - 1 / pi) / (1 - 1 / pi) if pi > 1 else None for si, pi in zip(s, p)]
    summary[impl] = {"threads": p.tolist(), "median_s": med.round(3).tolist(),
                     "speedup": s.round(2).tolist(), "efficiency": (s / p).round(2).tolist(),
                     "karp_flatt": [None if k is None else round(k, 3) for k in kf],
                     "amdahl_f": round(f, 3)}
pp = np.arange(1, 17)
a2.plot(pp, pp, color=MUTED, lw=1, ls=":", label="ідеальне S = p")
f0 = summary["partitioned"]["amdahl_f"]
a2.plot(pp, 1 / (f0 + (1 - f0) / pp), color=C[2], lw=1.2, ls="-.",
        label=f"Амдал, f = {f0:.3f}")
a2.axvline(6, color=GRID, lw=1)
a2.text(6.2, 12.5, "6 фізичних ядер", color=MUTED, fontsize=8)
a1.set(xlabel="кількість потоків p", ylabel="час побудови, с", xticks=agg.threads.unique())
a2.set(xlabel="кількість потоків p", ylabel="прискорення S(p) = T₁ / Tₚ",
       xticks=agg.threads.unique(), ylim=(0, 16.5))
a1.legend()
a2.legend(loc="upper left", fontsize=8)
a1.set_title("Медіана з 3 прогонів, межі — min…max", fontsize=9, color=MUTED, loc="left")
save(fig, "index_scaling.png")

fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.4))
for impl, (label, color, marker, ls) in IMPL.items():
    d = summary[impl]
    a1.plot(d["threads"], d["efficiency"], color=color, marker=marker, ls=ls, label=label)
    a2.plot(d["threads"][1:], d["karp_flatt"][1:], color=color, marker=marker, ls=ls,
            label=label)
a1.set(xlabel="кількість потоків p", ylabel="ефективність E(p) = S / p", ylim=(0, 1.05),
       xticks=agg.threads.unique())
a2.set(xlabel="кількість потоків p", ylabel="експериментальна послідовна частка e",
       xticks=agg.threads.unique()[1:])
a1.legend()
save(fig, "index_efficiency.png")

# phases: CPU time summed over workers
ph = idx.groupby(["impl", "threads"])[["read_cpu_ms", "tokenize_cpu_ms", "merge_cpu_ms",
                                        "map_merge_wall_ms", "finalize_wall_ms",
                                        "sync1", "sync2"]].median().reset_index()
g = ph[ph.impl == "partitioned"]
fig, ax = plt.subplots(figsize=(6.5, 3.6))
x = np.arange(len(g))
w = 0.62
bottom = np.zeros(len(g))
for col, name, color in [("read_cpu_ms", "читання файлів", C[3]),
                         ("tokenize_cpu_ms", "токенізація", C[0]),
                         ("merge_cpu_ms", "злиття в індекс", C[1])]:
    vals = g[col].to_numpy() / 1000
    ax.bar(x, vals, w, bottom=bottom, color=color, label=name, edgecolor="white", lw=1.5)
    bottom += vals
ax.set_xticks(x, g.threads)
ax.set(xlabel="кількість потоків p", ylabel="сумарний час CPU потоків, с")
ax.legend(loc="upper left", ncols=3, fontsize=8)
save(fig, "index_phases_cpu.png")
summary["phases_partitioned"] = g.to_dict(orient="list")
summary["phases_striped"] = ph[ph.impl == "striped"].to_dict(orient="list")

# ------------------------------------------------------------------ partitions
part = pd.read_csv(os.path.join(RES, "partitions.csv"))
if "mapper" in part.columns:
    part = part[part.mapper == "arena"]
both = pd.concat([part, idx[idx.threads.isin([1, 4, 8, 12])]])
pm = both.groupby(["impl", "partitions", "threads"]).time_ms.median().reset_index()
fig, axes = plt.subplots(1, 2, figsize=(10, 3.6), sharey=True)
for ax, impl in zip(axes, IMPL):
    for i, pcount in enumerate(sorted(pm.partitions.unique())):
        g2 = pm[(pm.impl == impl) & (pm.partitions == pcount)]
        ax.plot(g2.threads, g2.time_ms / 1000, color=C[i], marker="osD^"[i],
                ls=["-", "--", "-.", ":"][i], label=f"{pcount} партиц.")
    ax.set(title=IMPL[impl][0], xlabel="кількість потоків p", xticks=[1, 4, 8, 12])
    ax.title.set_fontsize(10)
axes[0].set_ylabel("час побудови, с")
axes[0].legend()
save(fig, "index_partitions.png")
summary["partitions"] = pm.to_dict(orient="list")
sync = part.groupby(["impl", "partitions", "threads"])[["merge_cpu_ms", "sync1", "sync2"]]
summary["partitions_sync"] = sync.median().reset_index().to_dict(orient="list")

fig, ax = plt.subplots(figsize=(6.5, 3.4))
st = part[part.impl == "striped"].groupby(["partitions", "threads"]).sync2.median().reset_index()
for i, pcount in enumerate(sorted(st.partitions.unique())):
    g2 = st[st.partitions == pcount]
    ax.plot(g2.threads, g2.sync2 / 1000, color=C[i], marker="osD"[i], ls=["-", "--", "-."][i],
            label=f"striped, {pcount} смуг")
ax.set(xlabel="кількість потоків p", ylabel="сумарне очікування блокувань, с",
       xticks=[1, 4, 8, 12])
ax.legend()
save(fig, "lock_wait.png")

# ------------------------------------------------------------------ mapper before/after
mh_path = os.path.join(RES, "mapper_hashmap.csv")
if os.path.exists(mh_path):
    mh = pd.read_csv(mh_path)
    old = mh.groupby("threads")[["time_ms", "tokenize_cpu_ms"]].median()
    new = idx[idx.impl == "partitioned"].groupby("threads")[["time_ms", "tokenize_cpu_ms"]].median()
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.6))
    for df, label, color, m, ls in [(old, "v1: String на кожен токен", C[1], "s", "--"),
                                    (new, "v2: байтова хеш-таблиця", C[0], "o", "-")]:
        a1.plot(df.index, df.time_ms / 1000, color=color, marker=m, ls=ls, label=label)
        a2.plot(df.index, df.time_ms.iloc[0] / df.time_ms, color=color, marker=m, ls=ls,
                label=label)
    a1.set(xlabel="кількість потоків p", ylabel="час побудови, с", xticks=old.index)
    a2.set(xlabel="кількість потоків p", ylabel="прискорення відносно власного T₁",
           xticks=old.index)
    a2.plot(old.index, old.index, color=MUTED, lw=1, ls=":", label="ідеальне")
    a1.legend()
    a2.legend(fontsize=8)
    save(fig, "mapper_compare.png")
    summary["mapper_v1"] = old.reset_index().to_dict(orient="list")
    summary["mapper_v2"] = new.reset_index().to_dict(orient="list")

# ------------------------------------------------------------------ memory
mem = pd.read_csv(os.path.join(RES, "memory.csv"))
fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.4), sharey=True)
for i, (mode, label) in enumerate([("no_gc", "без System.gc()"), ("gc", "з System.gc()")]):
    g3 = mem[mem["mode"] == mode]
    ax = (a1, a2)[i]
    ax.plot(g3.rebuild, g3.heap_used_mb / 1024, color=C[0], marker="o", label="heap used")
    ax.plot(g3.rebuild, g3.heap_committed_mb / 1024, color=C[1], marker="s", ls="--",
            label="heap committed")
    ax.plot(g3.rebuild, g3.rss_mb / 1024, color=C[2], marker="D", ls="-.", label="RSS процесу")
    ax.set(title=label, xlabel="номер перебудови", xticks=g3.rebuild)
    ax.title.set_fontsize(10)
a1.set_ylabel("пам'ять, ГіБ")
a1.legend(fontsize=8)
save(fig, "memory.png")
summary["memory"] = mem.to_dict(orient="list")

# ------------------------------------------------------------------ throughput
tp_path = os.path.join(RES, "throughput.csv")
if os.path.exists(tp_path):
    tp = pd.read_csv(tp_path)
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.6))
    a1.plot(tp.clients, tp.rps, color=C[0], marker="o")
    a1.set_xscale("log", base=2)
    a1.set_xticks(tp.clients, tp.clients)
    a1.set(xlabel="одночасних клієнтів", ylabel="запитів/с",
           title="Пропускна здатність (keyword)")
    for q, color, m, ls in [("p50_ms", C[0], "o", "-"), ("p95_ms", C[1], "s", "--"),
                            ("p99_ms", C[2], "D", "-.")]:
        a2.plot(tp.clients, tp[q], color=color, marker=m, ls=ls, label=q.split("_")[0])
    a2.set_xscale("log", base=2)
    a2.set_xticks(tp.clients, tp.clients)
    a2.set(xlabel="одночасних клієнтів", ylabel="латентність, мс", title="Латентність")
    a2.legend()
    for a in (a1, a2):
        a.title.set_fontsize(10)
    save(fig, "throughput.png")
    summary["throughput"] = tp.to_dict(orient="list")

llm_path = os.path.join(RES, "throughput_llm.csv")
if os.path.exists(llm_path):
    tl = pd.read_csv(llm_path)
    un = tl[tl["mode"] == "expand_uncached"]
    fig, (a1, a2) = plt.subplots(1, 2, figsize=(10, 3.4))
    a1.plot(un.clients, un.rps, color=C[1], marker="s", ls="--")
    a1.set(xlabel="одночасних клієнтів", ylabel="запитів/с",
           title="З LLM-розширенням (без кешу)", xticks=un.clients, ylim=(0, None))
    a2.plot(un.clients, un.p50_ms / 1000, color=C[0], marker="o", label="p50")
    a2.plot(un.clients, un.p95_ms / 1000, color=C[1], marker="s", ls="--", label="p95")
    a2.set(xlabel="одночасних клієнтів", ylabel="латентність, с", title="Латентність",
           xticks=un.clients)
    a2.legend()
    for a in (a1, a2):
        a.title.set_fontsize(10)
    save(fig, "throughput_llm.png")
    summary["throughput_llm"] = tl.to_dict(orient="list")

with open(os.path.join(RES, "summary.json"), "w", encoding="utf-8") as fh:
    json.dump(summary, fh, ensure_ascii=False, indent=1, default=float)
print("summary written")
