#!/usr/bin/env python3
"""Generates LaTeX table bodies for the report from bench/results (numbers with decimal comma)."""
import json
import os

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "bench", "results")
OUT = os.path.join(HERE, "tables")
os.makedirs(OUT, exist_ok=True)


def f(x, d=2):
    s = f"{x:,.{d}f}".replace(",", " ").replace(".", ",")
    return s.replace(" ", "\\,")


def write(name, rows):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as fh:
        fh.write("\n".join(rows) + "\n")


def kf(s, p):
    return (1 / s - 1 / p) / (1 - 1 / p) if p > 1 else None


idx = pd.read_csv(os.path.join(RES, "index.csv"))
idx = idx[idx.mapper == "arena"]
g = idx.groupby(["impl", "threads"]).time_ms.agg(["median", "min", "max"])
rows = []
t1 = {i: g.loc[(i, 1), "median"] for i in ("partitioned", "striped")}
for p in sorted(idx.threads.unique()):
    a = g.loc[("partitioned", p)]
    b = g.loc[("striped", p)]
    sa, sb = t1["partitioned"] / a["median"], t1["striped"] / b["median"]
    e = kf(sa, p)
    rows.append(f"{p} & {f(a['median']/1000)} & {f(a['min']/1000)}–{f(a['max']/1000)} & "
                f"{f(sa)} & {f(sa/p)} & {'—' if e is None else f(e, 3)} & "
                f"{f(b['median']/1000)} & {f(sb)} \\\\")
write("index.tex", rows)

ph = idx[idx.impl == "partitioned"].groupby("threads")[
    ["list_ms", "map_merge_wall_ms", "finalize_wall_ms", "read_cpu_ms", "tokenize_cpu_ms",
     "merge_cpu_ms", "sync1"]].median()
rows = []
for p, r in ph.iterrows():
    rows.append(f"{p} & {int(r.list_ms)} & {f(r.map_merge_wall_ms/1000)} & "
                f"{int(r.finalize_wall_ms)} & {f(r.read_cpu_ms/1000)} & "
                f"{f(r.tokenize_cpu_ms/1000)} & {f(r.merge_cpu_ms/1000)} & {int(r.sync1)} \\\\")
write("phases.tex", rows)

mh = pd.read_csv(os.path.join(RES, "mapper_hashmap.csv")).groupby("threads")[
    ["time_ms", "tokenize_cpu_ms"]].median()
mv = idx[idx.impl == "partitioned"].groupby("threads")[["time_ms", "tokenize_cpu_ms"]].median()
rows = []
for p in mh.index:
    o, n = mh.loc[p], mv.loc[p]
    rows.append(f"{p} & {f(o.time_ms/1000)} & {f(o.tokenize_cpu_ms/1000)} & "
                f"{f(n.time_ms/1000)} & {f(n.tokenize_cpu_ms/1000)} & "
                f"{f(o.time_ms/n.time_ms)} \\\\")
write("mapper.tex", rows)

part = pd.read_csv(os.path.join(RES, "partitions.csv"))
both = pd.concat([part, idx[idx.threads.isin([1, 4, 8, 12])]])
pm = both.groupby(["impl", "partitions", "threads"])[["time_ms", "merge_cpu_ms", "sync1",
                                                      "sync2"]].median()
rows = []
for impl, name in [("partitioned", "partitioned"), ("striped", "striped")]:
    for pc in (1, 8, 64, 256):
        cells = []
        for p in (1, 4, 8, 12):
            cells.append(f(pm.loc[(impl, pc, p)].time_ms / 1000))
        r12 = pm.loc[(impl, pc, 12)]
        sync = (f"{int(r12.sync1)} передач" if impl == "partitioned"
                else f"{f(r12.sync2/1000, 1)} с очік.")
        rows.append(f"{name} & {pc} & " + " & ".join(cells) +
                    f" & {f(r12.merge_cpu_ms/1000, 1)} & {sync} \\\\")
write("partitions.tex", rows)

tp = pd.read_csv(os.path.join(RES, "throughput.csv"))
rows = [f"{r.clients} & {f(r.rps, 1)} & {f(r.p50_ms)} & {f(r.p95_ms)} & {f(r.p99_ms)} & "
        f"{r.errors} \\\\" for r in tp.itertuples()]
write("throughput.tex", rows)

tl = pd.read_csv(os.path.join(RES, "throughput_llm.csv"))
names = {"expand_uncached": "без кешу", "expand_cached": "з кешем"}
rows = [f"{names[r.mode]} & {r.clients} & {f(r.rps, 1)} & {f(r.p50_ms, 1)} & "
        f"{f(r.p95_ms, 1)} \\\\" for r in tl.itertuples()]
write("llm.tex", rows)

mem = pd.read_csv(os.path.join(RES, "memory.csv"))
rows = []
for k in range(1, 7):
    a = mem[(mem["mode"] == "no_gc") & (mem.rebuild == k)].iloc[0]
    b = mem[(mem["mode"] == "gc") & (mem.rebuild == k)].iloc[0]
    rows.append(f"{k} & {a.heap_used_mb} & {a.heap_committed_mb} & {a.rss_mb} & "
                f"{b.heap_used_mb} & {b.heap_committed_mb} & {b.rss_mb} \\\\")
write("memory.tex", rows)

print("tables written to", OUT)
