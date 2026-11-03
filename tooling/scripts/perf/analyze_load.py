#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""汇总 load 阶段结果 -> 拐点识别 + 瓶颈线索（test-only）。

输入：tooling/jmeter/results/perf-2026-11-01/load/{baseline,stress,soak}/summary.json
输出：tooling/jmeter/results/perf-2026-11-01/load/analysis.md

判据（写死在此脚本，便于复算）：
  * 吞吐增益增益率 gain = (TPS_i - TPS_{i-1}) / TPS_{i-1}；当 gain < 0.25 视为已进入平台期；
  * 尾延迟膨胀 p95_i / p95_baseline > 3 视为排队开始；
  * 错误率 > 0（本轮全部端点应 2xx，任何非 2xx 都是容量或逻辑问题的信号）；
  * 三条中最早触发的那一档即「拐点并发」。
"""
import json
import os

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
LOAD = os.path.join(ROOT, "tooling", "jmeter", "results", "perf-2026-11-01", "load")


def load_phase(name):
    path = os.path.join(LOAD, name, "summary.json")
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def fmt(v, nd=1):
    if v is None:
        return "-"
    if isinstance(v, float):
        return ("%." + str(nd) + "f") % v
    return str(v)


def endpoint_rows(baseline, threads=None):
    out = []
    for run in (baseline or {}).get("runs", []):
        if threads is not None and run.get("threads") != threads:
            continue
        for label, d in ((run.get("summary") or {}).get("labels") or {}).items():
            out.append((run["plan"], label, d))
    return out


def knees(stress):
    """按 (plan) 聚合各并发档，给出拐点。"""
    by_plan = {}
    for run in (stress or {}).get("runs", []):
        s = run.get("summary") or {}
        if not s.get("requests"):
            continue
        by_plan.setdefault(run["plan"], []).append(
            {"threads": run["threads"], "tps": s.get("tps", 0), "p50": s.get("p50_ms"),
             "p95": s.get("p95_ms"), "p99": s.get("p99_ms"),
             "err": s.get("error_rate", 0), "req": s.get("requests", 0)})
    lines = []
    for plan, rows in sorted(by_plan.items()):
        rows.sort(key=lambda r: r["threads"])
        base = rows[0]
        prev_tps = None
        marks = []
        for r in rows:
            gain = None if prev_tps in (None, 0) else (r["tps"] - prev_tps) / prev_tps
            ratio = None if not base["p95"] else (r["p95"] or 0) / base["p95"]
            reason = ""
            if r["err"] > 0:
                reason = "错误率 %.2f%%" % r["err"]
            elif gain is not None and gain < 0.25:
                reason = "吞吐增益仅 %.0f%%" % (gain * 100)
            elif ratio is not None and ratio > 3:
                reason = "p95 膨胀 %.1fx" % ratio
            marks.append((r, gain, ratio, reason))
            prev_tps = r["tps"]
        knee = next((m for m in marks if m[3]), None)
        best = max(rows, key=lambda r: r["tps"])
        lines.append({
            "plan": plan, "rows": rows, "marks": marks,
            "knee": knee, "best": best, "base": base,
        })
    return lines


def label_ladders(stress):
    """按端点（JTL label）聚合各并发档 —— 交付物要求「每个端点都要有拐点描述」，
    而一个计划里混着快慢端点，只看计划级 TPS 会把慢端点的饱和点平均掉。"""
    by_label = {}
    for run in (stress or {}).get("runs", []):
        s = run.get("summary") or {}
        for label, d in (s.get("labels") or {}).items():
            if not d.get("n"):
                continue
            by_label.setdefault(label, []).append({
                "plan": run["plan"], "threads": run["threads"], "tps": d.get("tps", 0),
                "p50": d.get("p50_ms"), "p95": d.get("p95_ms"), "p99": d.get("p99_ms"),
                "avg": d.get("avg_ms"), "err": d.get("err_rate", 0), "req": d.get("n", 0),
                "codes": d.get("codes") or {}, "max": d.get("max_ms")})
    out = []
    for label, rows in sorted(by_label.items()):
        rows.sort(key=lambda r: r["threads"])
        # 同一端点可能在同一档位被两个计划采到（快端点混在慢计划里时，TPS 被计划跨度摊薄）。
        # 阶梯判据要求「一档一行」，取样本更多的那次为主行，另一次连同其 p95 注在行尾。
        merged = {}
        for r in rows:
            prev = merged.get(r["threads"])
            if prev is None or r["req"] > prev["req"]:
                if prev:
                    r = dict(r, also="%s n=%d tps=%s p95=%s" % (
                        prev["plan"], prev["req"], fmt(prev["tps"], 1), prev["p95"]))
                merged[r["threads"]] = r
            else:
                extra = "%s n=%d tps=%s p95=%s" % (
                    r["plan"], r["req"], fmt(r["tps"], 1), r["p95"])
                prev["also"] = (prev.get("also") + "；" + extra) if prev.get("also") else extra
        rows = [merged[k] for k in sorted(merged)]
        base = rows[0]
        prev_tps = None
        marks = []
        for r in rows:
            gain = None if prev_tps in (None, 0) else (r["tps"] - prev_tps) / prev_tps
            ratio = None if not base["p95"] else (r["p95"] or 0) / base["p95"]
            reason = ""
            non_2xx = {k: v for k, v in r["codes"].items()
                       if not (k.startswith("2") or k.startswith("3"))}
            if non_2xx:
                reason = "非 2xx %s" % non_2xx
            elif gain is not None and gain < 0.25:
                reason = "吞吐增益仅 %.0f%%" % (gain * 100)
            elif ratio is not None and ratio > 3:
                reason = "p95 膨胀 %.1fx" % ratio
            marks.append((r, gain, ratio, reason))
            prev_tps = r["tps"]
        out.append({"label": label, "rows": rows, "marks": marks,
                    "knee": next((m for m in marks if m[3]), None),
                    "best": max(rows, key=lambda r: r["tps"]), "base": base})
    return out


def main():
    baseline = load_phase("baseline")
    stress = load_phase("stress")
    supplement = load_phase("supplement")
    soak = load_phase("soak")
    # 补充轮（破坏性/外呼端点）与压力阶梯共用同一套拐点判据：
    # 它的样本量小且档位封顶，合在一起看才不会在报告里漏端点。
    ladder = {"runs": [*(stress or {}).get("runs", []), *(supplement or {}).get("runs", [])]} \
        if (stress or supplement) else None
    out = ["# 负载结果分析（自动生成，数据源见 load/*/summary.json）", ""]

    # 1) 基线端点表
    out.append("## 1. 基线（1 线程）逐端点")
    out.append("")
    out.append("| 计划 | 端点 | 样本 | TPS | avg | p50 | p95 | p99 | max | 错误率 |")
    out.append("|---|---|---|---|---|---|---|---|---|---|")
    eps = endpoint_rows(baseline)
    # 补充轮只取 1 线程档作为破坏性/外呼端点的基线（2/4 线程档属于阶梯，见 §2b）。
    eps += endpoint_rows(supplement, threads=1)
    eps.sort(key=lambda x: -(x[2].get("p99_ms") or 0))
    for plan, label, d in eps:
        out.append("| %s | %s | %d | %s | %s | %s | %s | %s | %s | %.2f%% |" % (
            plan, label, d["n"], fmt(d["tps"], 1), fmt(d["avg_ms"]), d["p50_ms"],
            d["p95_ms"], d["p99_ms"], d["max_ms"], d["err_rate"]))
    out.append("")
    out.append("## 2. 压力阶梯与拐点")
    for k in knees(ladder):
        out.append("")
        out.append("### %s" % k["plan"])
        out.append("")
        out.append("| 线程 | 样本 | TPS | p50 | p95 | p99 | 错误率 | 吞吐增益 | p95/基线 |")
        out.append("|---|---|---|---|---|---|---|---|---|")
        for r, gain, ratio, reason in k["marks"]:
            out.append("| %d | %d | %s | %s | %s | %s | %.2f%% | %s | %s%s |" % (
                r["threads"], r["req"], fmt(r["tps"], 1), r["p50"], r["p95"], r["p99"],
                r["err"], ("%.0f%%" % (gain * 100)) if gain is not None else "-",
                ("%.1fx" % ratio) if ratio else "-",
                (" ← " + reason) if reason else ""))
        if k["knee"]:
            r, _g, _ra, reason = k["knee"]
            out.append("")
            out.append("- **拐点并发 ≈ %d**（%s）；峰值吞吐 %.1f req/s @ %d 线程；"
                       "1 线程基线 %.1f req/s。"
                       % (r["threads"], reason, k["best"]["tps"], k["best"]["threads"],
                          k["base"]["tps"]))
        else:
            out.append("")
            out.append("- 到最高档仍未触发拐点判据（吞吐仍随线程增长）。")
    out.append("")
    out.append("## 2b. 逐端点阶梯与拐点")
    for k in label_ladders(ladder):
        out.append("")
        out.append("### %s  （计划 %s，基线 %s req/s @1 线程）"
                   % (k["label"], k["base"]["plan"], fmt(k["base"]["tps"], 1)))
        out.append("")
        out.append("| 线程 | 样本 | TPS | avg | p50 | p95 | p99 | max | 错误率 | 吞吐增益 | p95/基线 |")
        out.append("|---|---|---|---|---|---|---|---|---|---|---|")
        for r, gain, ratio, reason in k["marks"]:
            out.append("| %d | %d | %s | %s | %s | %s | %s | %s | %.2f%% | %s | %s%s%s |" % (
                r["threads"], r["req"], fmt(r["tps"], 1), fmt(r["avg"]), r["p50"], r["p95"],
                r["p99"], r["max"], r["err"],
                ("%.0f%%" % (gain * 100)) if gain is not None else "-",
                ("%.1fx" % ratio) if ratio else "-",
                (" ← " + reason) if reason else "",
                ("（同档另有 %s）" % r["also"]) if r.get("also") else ""))
        if k["knee"]:
            r, _g, _ra, reason = k["knee"]
            out.append("")
            out.append("- **拐点并发 ≈ %d**（%s）；峰值吞吐 %.1f req/s @ %d 线程。"
                       % (r["threads"], reason, k["best"]["tps"], k["best"]["threads"]))
        else:
            out.append("")
            out.append("- 到最高档（%d 线程）仍未触发拐点判据。" % k["rows"][-1]["threads"])

    out.append("")
    out.append("## 3. 资源峰值")
    out.append("")
    for name, ph in (("baseline", baseline), ("stress", stress),
                     ("supplement", supplement), ("soak", soak)):
        res = (ph or {}).get("resources") or {}
        if not res:
            continue
        out.append("### %s" % name)
        out.append("")
        out.append("| SUT | 样本 | RSS max | Private max | CPU avg | CPU max | 句柄 max |")
        out.append("|---|---|---|---|---|---|---|")
        for sut, v in res.items():
            if not isinstance(v, dict):
                out.append("| _note_ | %s | | | | | |" % v)
                continue
            out.append("| %s | %d | %.0fMB | %.0fMB | %.1f%% | %.1f%% | %d |" % (
                sut, v["samples"], v["rss_max_mb"], v["private_max_mb"],
                v["cpu_avg_pct"], v["cpu_max_pct"], v["handles_max"]))
        out.append("")
    if soak:
        cyc_path = os.path.join(LOAD, "soak", "cycles.json")
        cycles = []
        if os.path.isfile(cyc_path):
            with open(cyc_path, encoding="utf-8") as f:
                cycles = (json.load(f) or {}).get("cycles") or []
        out.append("## 4. Soak 周期")
        out.append("")
        if cycles:
            out.append("| 周期 | 读侧请求 | 读侧错误 | 读侧 TPS | 读侧 p99 | Harness 请求 | Harness 错误 | 写侧请求 | 写侧错误 |")
            out.append("|---|---|---|---|---|---|---|---|---|")
            for c in cycles:
                sr = c.get("server_read") or {}
                hr = c.get("harness") or {}
                wr = c.get("write") or {}
                out.append("| %s | %s | %s | %s | %s | %s | %s | %s | %s |" % (
                    c.get("cycle"), sr.get("requests"), sr.get("errors"), fmt(sr.get("tps"), 1),
                    sr.get("p99_ms"), hr.get("requests"), hr.get("errors"),
                    wr.get("requests"), wr.get("errors")))
        out.append("")

    probe_path = os.path.join(LOAD, "soak", "jvm-probe.json")
    if os.path.isfile(probe_path):
        with open(probe_path, encoding="utf-8") as f:
            probe = json.load(f) or {}
        out.append("## 5. Soak 进程侧泄漏判据（psutil 按 pid 采样）")
        out.append("")
        out.append("| SUT | 样本 | 时长(h) | 线程 首→末(峰) | 线程增速/h | 句柄 首→末(峰) | RSS 首→末(峰)MB | RSS 增速MB/h |")
        out.append("|---|---|---|---|---|---|---|---|")
        for name, v in (probe.get("summary") or {}).items():
            out.append("| %s | %d | %s | %d→%d(%d) | %s | %d→%d(%d) | %.0f→%.0f(%.0f) | %s |" % (
                name, v["samples"], v["span_hours"], v["threads_first"], v["threads_last"],
                v["threads_max"], v["threads_growth_per_hour"], v["handles_first"],
                v["handles_last"], v["handles_max"], v["rss_first_mb"], v["rss_last_mb"],
                v["rss_max_mb"], v["rss_growth_mb_per_hour"]))
        out.append("")
        out.append("- 判据：线程/句柄在末小时仍单调上行即视为泄漏；RSS 只看趋势（含堆内缓存，"
                   "作业快照回收后应回落）。")

    path = os.path.join(LOAD, "analysis.md")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out))
    print("[out]", path)


if __name__ == "__main__":
    main()
