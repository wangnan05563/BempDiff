#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""补齐「负载计划未覆盖端点」的 JMX（test-only，2026-11-01 第三轮）。

为什么单独成脚本：这些端点带**破坏性/外部依赖**语义，不能塞进无限循环的阶梯计划里——
  * POST /api/file op=rename|delete 会吃掉夹具文件本身，第二次循环就 404；
  * POST /api/export/{id}/delete 会删掉记录，后续 detail/download 必然失败；
  * POST /api/ai/context（刷新）重建整个项目上下文索引，POST /api/update/check 打外网。
所以它们用「有限循环 + 思考时间」测，样本数由 -Jloops 控制，并在报告里如实标注样本量。

可重复的写侧（op=copy：把差异节点跨侧复制，重复执行只是覆盖同一文件）仍进常规阶梯。

用法：
  python tooling/scripts/perf/gen_mutate_jmx.py
  jmeter -n -t tooling/jmeter/load/srv_mutate.jmx -Jthreads=1 -Jduration=60 -JjobFolder=...
"""
import os

from gen_load_jmx import (OUT, SMALL_L, _arg, constant_timer, plan, sampler, write)

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))

# 夹具 folders/v1 vs v2 的已知差异节点（见 gen_fixtures.py）：
#   ADDED  ：com/bempdiff/gen/ExtraA.class、ExtraB.class（仅右/新侧）
#   DELETED：com/bempdiff/gen/C17.class、C22.class（仅左/旧侧）
#   MODIFIED：C00..C16 等两侧都有的文件
KEY_ADDED = "com/bempdiff/gen/ExtraA.class"
KEY_DELETED = "com/bempdiff/gen/C17.class"
KEY_RENAME_SRC = "com/bempdiff/gen/C18.class"
KEY_RENAME_DST = "com/bempdiff/gen/perf_renamed.class"


def file_body(op, key_expr, new_name=None):
    if new_name:
        return ('{"jobId":"${__P(jobFolder,)}","key":"%s","op":"%s","newName":"%s"}'
                % (key_expr, op, new_name))
    return '{"jobId":"${__P(jobFolder,)}","key":"%s","op":"%s"}' % (key_expr, op)


# ------------------------------------------------------------------ 可进阶梯的写侧
S_MUTATE = [
    ("M file copy r2l(ADDED)", "/api/file", None, "POST", file_body("copy", KEY_ADDED)),
    ("M file copy l2r(DELETED)", "/api/file", None, "POST", file_body("copy", KEY_DELETED)),
    ("M job cancel", "/api/job/${__P(jobSmall,)}/cancel", None, "POST", None),
    ("M export alias", "/api/export", None, "GET", None),
    ("M ai context GET", "/api/ai/context", None, "GET", None),
]

# ------------------------------------------------------------------ 单发/有限循环
S_MUTATE_ONCE = [
    ("O file rename", "/api/file", None, "POST",
     file_body("rename", KEY_RENAME_SRC, "perf_renamed.class"), 600000, "${__P(thinkMs,3000)}"),
    ("O file delete", "/api/file", None, "POST", file_body("delete", KEY_RENAME_DST),
     600000, "${__P(thinkMs,3000)}"),
    ("O export delete", "/api/export/${__P(exportId2,)}/delete", None, "POST", None,
     600000, "${__P(thinkMs,3000)}"),
    ("O ai context POST", "/api/ai/context", None, "POST", "{}", 600000, "${__P(thinkMs,10000)}"),
    ("O update check", "/api/update/check", None, "POST", "{}", 600000, "${__P(thinkMs,10000)}"),
]

S_EXPORT_REC = [
    ("X export detail", "/api/export/${__P(exportId,)}", None, "GET", None),
    # 单个 zip 272MB：带思考时间，避免把环回带宽和磁盘读满，测的是「下载这份包要多久」
    ("X export download", "/api/export/${__P(exportId,)}/download", None, "GET", None,
     900000, "${__P(thinkMs,5000)}"),
]


def finite_group(name, samplers):
    """有限循环组：次数由 -Jloops 控制，不受 -Jduration 调度器影响。"""
    parts = []
    for t in samplers:
        parts.append(sampler(*t[:6]))
        think = t[6] if len(t) > 6 else None
        parts.append("<hashTree>%s</hashTree>" % (constant_timer(think) if think else ""))
    return (
        '<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="%s" enabled="true">'
        '<stringProp name="ThreadGroup.on_sample_error">continue</stringProp>'
        '<elementProp name="ThreadGroup.main_controller" elementType="LoopController" '
        'guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">'
        '<boolProp name="LoopController.continue_forever">false</boolProp>'
        '<stringProp name="LoopController.loops">${__P(loops,1)}</stringProp>'
        '</elementProp>'
        '<stringProp name="ThreadGroup.num_threads">${__P(threads,1)}</stringProp>'
        '<stringProp name="ThreadGroup.ramp_time">${__P(ramp,1)}</stringProp>'
        '<boolProp name="ThreadGroup.scheduler">false</boolProp>'
        '<stringProp name="ThreadGroup.duration"></stringProp>'
        '<stringProp name="ThreadGroup.delay"></stringProp>'
        '</ThreadGroup><hashTree>%s</hashTree>' % (_x(name), "".join(parts))
    )


def _x(s):
    import xml.sax.saxutils as X
    return X.escape(s)


def main():
    write("srv_mutate.jmx", plan("srv_mutate", [_tg("load", S_MUTATE)]))
    write("srv_mutate_once.jmx", plan("srv_mutate_once", [finite_group("once", S_MUTATE_ONCE)]))
    write("srv_export_rec.jmx", plan("srv_export_rec", [finite_group("rec", S_EXPORT_REC)]))
    print("MUTATE JMX DONE ->", OUT)


def _tg(name, samplers):
    """常规无限循环组（与 gen_load_jmx.thread_group 同构，scheduler 受 -Jduration 控制）。"""
    parts = []
    for t in samplers:
        parts.append(sampler(*t[:6]))
        parts.append("<hashTree/>")
    return (
        '<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="%s" enabled="true">'
        '<stringProp name="ThreadGroup.on_sample_error">continue</stringProp>'
        '<elementProp name="ThreadGroup.main_controller" elementType="LoopController" '
        'guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">'
        '<boolProp name="LoopController.continue_forever">false</boolProp>'
        '<stringProp name="LoopController.loops">-1</stringProp>'
        '</elementProp>'
        '<stringProp name="ThreadGroup.num_threads">${__P(threads,1)}</stringProp>'
        '<stringProp name="ThreadGroup.ramp_time">${__P(ramp,1)}</stringProp>'
        '<boolProp name="ThreadGroup.scheduler">true</boolProp>'
        '<stringProp name="ThreadGroup.duration">${__P(duration,60)}</stringProp>'
        '<stringProp name="ThreadGroup.delay"></stringProp>'
        '<boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>'
        '</ThreadGroup><hashTree>%s</hashTree>' % (_x(name), "".join(parts))
    )


if __name__ == "__main__":
    main()
