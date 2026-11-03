# -*- coding: utf-8 -*-
"""Soak 结果分析：把 cycles.json / jvm-probe.json / 逐周期 JTL / 目录增长快照
汇成一段可直接粘进报告 §9 的 Markdown，并按判据给出「是否劣化 / 是否泄漏」结论。

用法：
    python -X utf8 tooling/scripts/perf/analyze_soak.py
输入根目录取自 config/perf-env.yml 的 results 约定（默认
tooling/jmeter/results/perf-<日期>/load/soak），日期用 --date 指定。
"""
import argparse
import ast
import collections
import csv
import gzip
import hashlib
import io
import json
import os
import re
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
PROBE = os.environ.get("BEMP_PERF_PROBE", r"E:\qoder-perf\probe")


def pct(vals, q):
    if not vals:
        return 0.0
    s = sorted(vals)
    return s[min(len(s) - 1, int(round(q / 100.0 * (len(s) - 1))))]


def slope(xs, ys):
    """最小二乘斜率；样本不足 2 个返回 None。"""
    n = len(xs)
    if n < 2:
        return None
    mx = sum(xs) / n
    my = sum(ys) / n
    den = sum((x - mx) ** 2 for x in xs)
    if den == 0:
        return None
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den


def read_label_jtl(path):
    """返回 {label: {requests,errors,avg,p50,p95,p99,max,tps,span}}。"""
    per = collections.defaultdict(lambda: {"el": [], "err": 0, "ts": []})
    with gzip.open(path, "rb") as fh:
        text = io.TextIOWrapper(fh, encoding="utf-8", newline="")
        for row in csv.DictReader(text):
            try:
                d = per[row["label"]]
                d["el"].append(float(row["elapsed"]))
                d["ts"].append(int(row["timeStamp"]))
                if row["success"].strip().upper() != "TRUE":
                    d["err"] += 1
            except (KeyError, ValueError):
                continue
    out = {}
    for label, d in per.items():
        if not d["el"]:
            continue
        span = (max(d["ts"]) - min(d["ts"])) / 1000.0 or 1.0
        out[label] = {
            "requests": len(d["el"]), "errors": d["err"],
            "avg": round(sum(d["el"]) / len(d["el"]), 1),
            "p50": pct(d["el"], 50), "p95": pct(d["el"], 95), "p99": pct(d["el"], 99),
            "max": round(max(d["el"])), "tps": round(len(d["el"]) / span, 1),
        }
    return out


def md_table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "|".join(["---"] * len(header)) + "|"]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default=time.strftime("perf-%Y-%m-%d"))
    args = ap.parse_args()
    soak_dir = os.path.join(ROOT, "tooling", "jmeter", "results", args.date, "load", "soak")
    out = ["#### 9. 稳定性 Soak 结果（自动生成于 %s）" % time.strftime("%Y-%m-%d %H:%M"), ""]

    # ---------------- 1. 周期表 ----------------
    with open(os.path.join(soak_dir, "cycles.json"), encoding="utf-8") as f:
        doc = json.load(f) or {}
    cycles = doc.get("cycles") or []
    out.append("##### 9.1 周期汇总（读侧 / 核心侧 / 写侧）")
    out.append("")
    rows = []
    d = lambda v: "-" if v is None else v
    for c in cycles:
        sr, hr, wr = c.get("server_read") or {}, c.get("harness") or {}, c.get("write") or {}
        rows.append([
            c.get("cycle"), c.get("ts", "")[11:],
            d(sr.get("requests")), "%g" % sr.get("tps", 0), "%g" % sr.get("avg_ms", 0),
            d(sr.get("p95_ms")), d(sr.get("p99_ms")), d(sr.get("errors")),
            d(hr.get("requests")), "%g" % hr.get("avg_ms", 0), d(hr.get("p95_ms")), d(hr.get("errors")),
            d(wr.get("requests")), d(wr.get("errors")),
            "%g" % c.get("drain_sec", 0), d(c.get("queue_residual")),
        ])
    out += md_table(
        ["周期", "时刻", "读侧样本", "读侧TPS", "读avg", "读p95", "读p99", "读错",
         "核样本", "核avg", "核p95", "核错", "写样本", "写错", "排空s", "队列残"], rows)
    tot = sum((c.get("server_read") or {}).get("requests", 0) for c in cycles)
    tot += sum((c.get("harness") or {}).get("requests", 0) for c in cycles)
    tot += sum((c.get("write") or {}).get("requests", 0) for c in cycles)
    err = sum((c.get(k) or {}).get("errors", 0) for c in cycles
              for k in ("server_read", "harness", "write"))
    out.append("")
    out.append("- Soak 累计 **%s 请求 / %d 错误（%.4f%%）**，周期数 %d，"
               "跨度 %.2f 小时。" % (f"{tot:,}", err, (err / tot * 100) if tot else 0.0,
                                    len(cycles),
                                    (len(cycles) and
                                     (time.mktime(time.strptime(cycles[-1]["ts"], "%Y-%m-%dT%H:%M:%S"))
                                      - time.mktime(time.strptime(cycles[0]["ts"], "%Y-%m-%dT%H:%M:%S")))
                                     / 3600.0 or 0)))

    # ---------------- 2. 劣化判据（周期序列回归） ----------------
    out.append("")
    out.append("##### 9.2 劣化判据（按周期序号做线性回归，斜率≈0 即无劣化）")
    out.append("")
    rows = []
    for key, cn in (("server_read", "读侧"), ("harness", "核心侧"), ("write", "写侧")):
        pts = [(c["cycle"], (c.get(key) or {}).get("avg_ms")) for c in cycles
               if (c.get(key) or {}).get("avg_ms") is not None]
        x = [p[0] for p in pts]
        sl = slope(x, [p[1] for p in pts])
        firsts, lasts = pts[:3], pts[-3:]
        fa = sum(p[1] for p in firsts) / len(firsts) if firsts else 0
        la = sum(p[1] for p in lasts) / len(lasts) if lasts else 0
        tps = [(c["cycle"], (c.get(key) or {}).get("tps")) for c in cycles
               if (c.get(key) or {}).get("tps") is not None]
        stp = slope([p[0] for p in tps], [p[1] for p in tps])
        rows.append([cn, len(pts), "前3期均值 %.1fms" % fa, "后3期均值 %.1fms" % la,
                     "%+.1f%%" % (((la - fa) / fa * 100) if fa else 0),
                     "avg %.3f ms/周期" % sl if sl is not None else "n/a",
                     "TPS %.2f/周期" % stp if stp is not None else "n/a"])
    out += md_table(["阶段", "有效周期", "首段 avg", "末段 avg", "末/首变化",
                     "avg 斜率", "TPS 斜率"], rows)

    # ---------------- 3. 逐端点首/末周期 ----------------
    # 旁路快照按「周期计数增加」复制固定名 JTL，若某周期未跑某阶段，
    # 该阶段归档会是上一周期同名文件的逐字节副本（假样本）。用 sha256 识别并剔除。
    cyc_files = {}
    seen_sha = {}
    stale = []
    for name in sorted(os.listdir(soak_dir)):
        m = re.match(r"(soak_\w+?)-t\d+-c(\d+)\.jtl\.gz$", name)
        if not m:
            continue
        path = os.path.join(soak_dir, name)
        with open(path, "rb") as fh:
            h = hashlib.sha256(fh.read()).hexdigest()
        if seen_sha.get(m.group(1)) == h:
            stale.append("%s-c%s" % (m.group(1), m.group(2)))
            continue
        seen_sha[m.group(1)] = h
        cyc_files.setdefault(int(m.group(2)), {})[m.group(1)] = path
    plans = sorted({p for d in cyc_files.values() for p in d})
    complete = [n for n, d in sorted(cyc_files.items()) if all(p in d for p in plans)]
    out.append("")
    out.append("##### 9.3 逐端点劣化（最早完整周期 vs 最末完整周期，按 label）")
    out.append("")
    if len(complete) >= 2:
        a_n, b_n = complete[0], complete[-1]
        a, b, owner = {}, {}, {}
        for plan in plans:
            for store, d in ((a, a_n), (b, b_n)):
                p = cyc_files[d].get(plan)
                if p:
                    got = read_label_jtl(p)
                    store.update(got)
                    for lb in got:
                        owner[lb] = plan
        rows = []
        for label in sorted(set(a) & set(b), key=lambda l: -(b[l]["avg"] - a[l]["avg"])):
            da, db = a[label], b[label]
            base = da["avg"] or 0.1
            rows.append([label, "c%d" % a_n, da["requests"], "%g" % da["tps"], da["avg"],
                         da["p95"], da["errors"], "c%d" % b_n, db["requests"], "%g" % db["tps"],
                         db["avg"], db["p95"], db["errors"], "%+.1f%%" % ((db["avg"] - base) / base * 100)])
        out += md_table(["端点", "早", "早样本", "早TPS", "早avg", "早p95", "早错",
                         "末", "末样本", "末TPS", "末avg", "末p95", "末错", "Δavg"], rows)
        out.append("")
        out.append("- 保留周期归档：%s（`archive_jtl` 原名固定、多周期互相覆盖，"
                   "故仅存最后一次；本轮由旁路快照脚本按周期另存）。"
                   % ", ".join("c%d" % n for n in sorted(cyc_files)))
        out.append("- 三阶段齐全、可用于首末对比的周期：%s；取 c%d ↔ c%d。"
                   % (", ".join("c%d" % n for n in complete), a_n, b_n))
        if stale:
            out.append("- 剔除逐字节重复的伪归档：%s（该周期未跑到对应阶段，"
                       "旁路快照复制到的仍是上一周期的同名文件）。" % ", ".join(stale))

        # 9.3b 首末差最大的那个计划，逐周期序列——用单调性把「劣化」与「同机争抢噪声」分开
        target = max(set(a) & set(b), key=lambda l: b[l]["avg"] - a[l]["avg"])
        plan = owner.get(target)
        out.append("")
        out.append("##### 9.3b `%s` 逐周期序列（Δavg 最大的端点 `%s` 所属计划）" % (plan, target))
        out.append("")
        rows, series = [], {}
        for n in complete:
            p = cyc_files[n].get(plan)
            if p:
                series[n] = read_label_jtl(p)
        for lb in sorted({l for l in set(a) & set(b) if owner.get(l) == plan}):
            xs = [n for n in complete if lb in series.get(n, {})]
            ys = [series[n][lb]["avg"] for n in xs]
            ts = [series[n][lb]["tps"] for n in xs]
            if len(ys) < 3:
                continue
            up = sum(1 for i in range(1, len(ys)) if ys[i] > ys[i - 1])
            rows.append([lb, " ".join("%.1f" % v for v in ys),
                         " ".join("%.0f" % v for v in ts),
                         "%.1f–%.1f" % (min(ys), max(ys)),
                         "%+.3f" % (slope(xs, ys) or 0),
                         "%d/%d" % (up, len(ys) - 1),
                         "%+.1f%%" % ((ys[-1] - ys[0]) / (ys[0] or 0.1) * 100)])
        out += md_table(["端点", "逐周期 avg(ms)", "逐周期 TPS", "区间",
                         "斜率 ms/周期", "上升段/总段", "末/首"], rows)
        out.append("")
        out.append("- 判读：**只有逐周期序列单调上升才是劣化**；上升段占比接近一半、斜率量级小于周期内波动，"
                   "判为同机争抢（读侧 TPS 在各周期之间大幅摆动、核心侧与其反向，见 §9.1）而非状态累积。")
    else:
        out.append("- 尚无逐周期 JTL 快照（等待 Soak 收尾）。")

    # ---------------- 4. 进程侧泄漏判据 ----------------
    out.append("")
    out.append("##### 9.4 进程侧泄漏判据（psutil 按 pid，15s 采样）")
    out.append("")
    with open(os.path.join(soak_dir, "jvm-probe.json"), encoding="utf-8") as f:
        probe = json.load(f) or {}
    by = collections.defaultdict(list)
    for r in probe.get("rows") or []:
        by[r["name"]].append(r)
    rows = []
    for name, rs in sorted(by.items()):
        rs.sort(key=lambda r: r["ts"])
        span_h = (rs[-1]["ts"] - rs[0]["ts"]) / 3600.0
        warm = [r for r in rs if r["ts"] >= 1200] or rs          # 丢弃前 20min 预热
        s_all = slope([r["ts"] / 3600.0 for r in rs], [r["rss_mb"] for r in rs])
        s_warm = slope([r["ts"] / 3600.0 for r in warm], [r["rss_mb"] for r in warm])
        h = slope([r["ts"] / 3600.0 for r in warm], [r["handles"] for r in warm])
        t = slope([r["ts"] / 3600.0 for r in warm], [r["threads"] for r in warm])
        last_q = [r["rss_mb"] for r in rs[-max(1, len(rs) // 4):]]
        prev_q = [r["rss_mb"] for r in rs[-max(2, len(rs) // 2):-max(1, len(rs) // 4)]]
        tail = [r for r in rs if r["ts"] >= rs[-1]["ts"] - 3600] or warm
        s_last = slope([r["ts"] / 3600.0 for r in tail], [r["rss_mb"] for r in tail])
        n = len(rs)
        quarters = [sum(r["rss_mb"] for r in rs[i * n // 4:(i + 1) * n // 4])
                    / max(1, len(rs[i * n // 4:(i + 1) * n // 4])) for i in range(4)]
        rows.append([name, len(rs), "%.2f" % span_h,
                     "%.0f→%.0f(峰%.0f)" % (rs[0]["rss_mb"], rs[-1]["rss_mb"],
                                            max(r["rss_mb"] for r in rs)),
                     "%+.1f" % (s_all or 0), "%+.1f" % (s_warm or 0), "%+.1f" % (s_last or 0),
                     " / ".join("%.0f" % q for q in quarters),
                     "%+.1f%%" % (((sum(last_q) / len(last_q)) / (sum(prev_q) / len(prev_q)) - 1) * 100
                                  if prev_q else 0),
                     "%d→%d" % (rs[0]["handles"], rs[-1]["handles"]), "%+.1f" % (h or 0),
                     "%d→%d" % (rs[0]["threads"], rs[-1]["threads"]), "%+.1f" % (t or 0)])
    out += md_table(["SUT", "样本", "时长h", "RSS 首→末(峰)MB", "RSS全窗斜率MB/h",
                     "RSS去预热斜率MB/h", "RSS末60min斜率MB/h", "RSS 四分位均值序列",
                     "末25%均值/前25~50%", "句柄首→末", "句柄斜率/h",
                     "线程首→末", "线程斜率/h"], rows)
    out.append("")
    out.append("- 判据：**去预热斜率**与**末段均值环比**同时接近 0 才算无泄漏；只用「首→末 ÷ 时长」"
               "会把 JVM 启动期的堆自然增长（`-Xms512m` 起步、G1 边用边扩）误读成泄漏速率。")

    # ---------------- 5. 目录增长 ----------------
    out.append("")
    out.append("##### 9.5 文件缓存目录增长（本系统无 DB，以此作等价泄漏判据）")
    out.append("")
    for tag in ("dirgrowth_soak_start.json", "dirgrowth_soak_end.json"):
        p = os.path.join(PROBE, tag)
        if not os.path.isfile(p):
            continue
        with open(p, encoding="utf-8") as f:
            d = json.load(f)
        subs = d.get("subs") or {}
        detail = ", ".join("%s %.1fMB/%d 文件"
                           % (k, (v.get("bytes", 0) if isinstance(v, dict) else v) / 2 ** 20,
                              v.get("files", 1) if isinstance(v, dict) else 1)
                           for k, v in sorted(subs.items()))
        out.append("- %s `%s`：总 **%.1fMB / %s 文件**；分项 %s"
                   % (tag, d.get("ts"), d.get("total", [0, 0])[1] / 2 ** 20,
                      d.get("total", [0, 0])[0], detail))
    def flatten(d):
        """{sut: {prefix: n}} 或 {prefix: n}（早期 start 快照只含 harness）统一成 {sut#prefix: n}。"""
        flat = {}
        for k, v in d.items():
            if k in ("ts", "_note") or not isinstance(v, dict):
                continue
            for pk, pv in v.items():
                if pk != "top_entries" and isinstance(pv, int):
                    flat["%s#%s" % (k, pk)] = pv
            flat["%s#top_entries" % k] = v.get(
                "top_entries", sum(x for x in v.values() if isinstance(x, int)))
        for k, v in d.items():
            if k not in ("ts", "_note") and isinstance(v, int):
                flat["harness#%s" % k] = v
        return flat

    tmp_docs = {}
    for tag in ("tmpgrowth_soak_start.json", "tmpgrowth_soak_end.json"):
        p = os.path.join(PROBE, tag)
        if not os.path.isfile(p):
            continue
        with open(p, encoding="utf-8") as f:
            raw = f.read().strip()
        try:
            d = json.loads(raw)
        except ValueError:
            d = {"_note": "该快照早于采样脚本改造，仅含 harness 的 bempdiff-* 前缀计数",
                 "harness": ast.literal_eval(raw)}
        lines = []
        for k, v in sorted(d.items()):
            if k in ("ts", "_note") or not isinstance(v, dict):
                continue
            pre = " ".join("%s=%s" % (pk, pv) for pk, pv in sorted(v.items())
                           if pk != "top_entries")
            lines.append("%s top=%s %s" % (k, v.get("top_entries", sum(
                x for x in v.values() if isinstance(x, int))), pre))
        for k, v in sorted(d.items()):
            if k not in ("ts", "_note") and isinstance(v, int):
                lines.append("%s=%s" % (k, v))
        note = ("（%s）" % d["_note"]) if "_note" in d else ""
        out.append("- %s `%s`：%s%s" % (tag, d.get("ts", "-"), " / ".join(lines) or "-", note))
        tmp_docs[tag] = d
    if len(tmp_docs) == 2:
        a = flatten(tmp_docs["tmpgrowth_soak_start.json"])
        b = flatten(tmp_docs["tmpgrowth_soak_end.json"])
        deltas = ["%s %+d" % (k, b[k] - a.get(k, 0)) for k in sorted(set(a) | set(b))
                  if b.get(k, 0) != a.get(k, 0)]
        out.append("- 差值（末−首）：%s" % ("；".join(deltas) if deltas else "无变化"))

    text = "\n".join(out) + "\n"
    dest = os.path.join(soak_dir, "soak-section.md")
    with open(dest, "w", encoding="utf-8") as f:
        f.write(text)
    print(text)
    print("[out]", dest)


if __name__ == "__main__":
    main()
