#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成基线/压力/Soak 用 JMX（test-only）。

与 gen_jmx.py 的区别：
  - gen_jmx.py 面向 test-only Harness 的「无状态 GET」；
  - 本脚本同时覆盖产品真实 API（有状态：jobId / exportId 由编排层 seed 后经 -J 传入）。
线程数 / 时长 / ramp 全部用 ${__P(...)} 参数化，跑阶梯时无需重新生成 JMX。

用法：
  python tooling/scripts/perf/gen_load_jmx.py
  jmeter -n -t tooling/jmeter/load/srv_read.jmx -Jport=18090 -Jthreads=1 -Jduration=60 \
         -JjobSmall=job-1 -JjobBig=job-2 -JexportId=exp_x -l <jtl>
"""
import os
import xml.sax.saxutils as X

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
OUT = os.path.join(ROOT, "tooling", "jmeter", "load")
os.makedirs(OUT, exist_ok=True)

# 相对项目根；JMeter/服务端启动时 cwd 必须是项目根
SMALL_L = "bempdiff/fixtures/small_real_v1.jar"
SMALL_R = "bempdiff/fixtures/small_real_v2.jar"
BIG_L = "bempdiff/fixtures/big200_v1.jar"
BIG_R = "bempdiff/fixtures/big200_v2.jar"
LIB_L = "bempdiff/lib_v1.jar"
LIB_R = "bempdiff/lib_v2.jar"


def _arg(name, value, use_equals=True, encode=True):
    return (
        '<elementProp name="%s" elementType="HTTPArgument">'
        '<boolProp name="HTTPArgument.always_encode">%s</boolProp>'
        '<stringProp name="Argument.name">%s</stringProp>'
        '<stringProp name="Argument.value">%s</stringProp>'
        '<stringProp name="Argument.metadata">=</stringProp>'
        '<boolProp name="HTTPArgument.use_equals">%s</boolProp>'
        '</elementProp>'
        % (X.escape(name), "true" if encode else "false", X.escape(name), X.escape(str(value)),
           "true" if use_equals else "false")
    )


def sampler(label, path, params=None, method="GET", body=None, timeout=600000):
    """params: dict -> query/form；body: JSON 字符串 -> raw POST body。

    注意：raw body 必须 always_encode=false，否则 JMeter 会对请求体做百分号编码，
    服务端 Json.parseObject 收到 %7B... 直接 NumberFormatException（实测 2026-11-01）。
    """
    if body is not None:
        args = _arg("", body, use_equals=False, encode=False)
    else:
        args = "".join(_arg(k, v) for k, v in (params or {}).items())
    return (
        '<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" '
        'testname="%s" enabled="true">'
        '<elementProp name="HTTPsampler.Arguments" elementType="Arguments" '
        'guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">'
        '<collectionProp name="Arguments.arguments">%s</collectionProp>'
        '</elementProp>'
        '<stringProp name="HTTPSampler.domain">${__P(host,127.0.0.1)}</stringProp>'
        '<stringProp name="HTTPSampler.port">${__P(port,18090)}</stringProp>'
        '<stringProp name="HTTPSampler.protocol">http</stringProp>'
        '<stringProp name="HTTPSampler.path">%s</stringProp>'
        '<stringProp name="HTTPSampler.method">%s</stringProp>'
        '<boolProp name="HTTPSampler.follow_redirects">true</boolProp>'
        '<boolProp name="HTTPSampler.use_keepalive">true</boolProp>'
        '<boolProp name="HTTPSampler.postBodyRaw">%s</boolProp>'
        '<stringProp name="HTTPSampler.connect_timeout">10000</stringProp>'
        '<stringProp name="HTTPSampler.response_timeout">%d</stringProp>'
        '</HTTPSamplerProxy>'
        % (X.escape(label), args, X.escape(path), method,
           "true" if body is not None else "false", timeout)
    )


def constant_timer(ms):
    delay = ms if isinstance(ms, str) else int(ms)
    return ('<ConstantTimer guiclass="ConstantTimerGui" testclass="ConstantTimer" '
            'testname="think" enabled="true">'
            '<stringProp name="ConstantTimer.delay">%s</stringProp></ConstantTimer>' % delay)


def thread_group(name, samplers):
    """元组第 7 位（可选）= 思考时间 ms：贴近真实业务「提交后等结果」的节奏，
    而不是把作业提交端点打成纯理论峰值（约束：并发模型需贴近真实业务场景）。"""
    parts = []
    for t in samplers:
        parts.append(sampler(*t[:6]))
        think = t[6] if len(t) > 6 else None
        parts.append("<hashTree>%s</hashTree>" % (constant_timer(think) if think else ""))
    s = "".join(parts)
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
        '</ThreadGroup><hashTree>%s</hashTree>' % (X.escape(name), s)
    )


def plan(testname, groups):
    g = "".join(groups)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">\n'
        '<hashTree>\n'
        '<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="%s" enabled="true">'
        '<stringProp name="TestPlan.comments">BempDiff perf load (test-only)</stringProp>'
        '<boolProp name="TestPlan.functional_mode">false</boolProp>'
        '<boolProp name="TestPlan.tearDown_on_shutdown">true</boolProp>'
        '<boolProp name="TestPlan.serialize_threadgroups">false</boolProp>'
        '<elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" '
        'testclass="Arguments" testname="User Defined Variables" enabled="true">'
        '<collectionProp name="Arguments.arguments"/></elementProp>'
        '<stringProp name="TestPlan.user_define_classpath"></stringProp>'
        '</TestPlan>\n<hashTree>\n%s\n</hashTree>\n</hashTree>\n</jmeterTestPlan>\n'
        % (X.escape(testname), g)
    )


def write(name, xml):
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote", path)


# ---------------------------------------------------------------- 真实 API（18090）
# 分组原则：服务端每次新比对都会 sweepSupersededJobs —— 只保留「最近结束的 1 个终态作业」的
# 双侧快照，其余作业的快照被释放（见 BempServer.sweepSupersededJobs）。
# 因此同一计划集内不能出现「提交新比对」与「依赖旧作业快照」混跑，否则后者必然 500/断连。
# 每组计划前由编排层单独 seed 对应作业，组内不再创建新作业。
S_SESSION = [
    ("S status small", "/api/job/${__P(jobSmall,)}/status", None, "GET", None),
    ("S report GET", "/api/job/${__P(jobSmall,)}/report", None, "GET", None),
    ("S report POST", "/api/job/${__P(jobSmall,)}/report", None, "POST", '{"ai":false}'),
    ("S unpack-report", "/api/job/${__P(jobSmall,)}/unpack-report", None, "GET", None),
    ("S entry decompile", "/api/entry/decompile", {"jobId": "${__P(jobSmall,)}", "key": "${__P(keySmall,)}"}, "GET", None),
    ("S export list", "/api/export/list", None, "GET", None),
    ("S config GET", "/api/config", None, "GET", None),
    ("S tasks running", "/api/tasks/running", None, "GET", None),
    ("S static index", "/index.html", None, "GET", None),
]

S_ARCHIVE = [
    ("S status nested", "/api/job/${__P(jobNested,)}/status", None, "GET", None),
    ("S entry children", "/api/entry/children", {"jobId": "${__P(jobNested,)}", "key": "${__P(keyNested,)}"}, "GET", None),
    ("S entry recursive", "/api/entry/recursive", {"jobId": "${__P(jobNested,)}", "key": "${__P(keyNested,)}"}, "GET", None),
]

S_FILE = [
    ("S status folder", "/api/job/${__P(jobFolder,)}/status", None, "GET", None),
    ("S file info", "/api/file", None, "POST", '{"jobId":"${__P(jobFolder,)}","key":"${__P(keyFolder,)}","op":"info"}'),
]

S_EXPORT = [
    ("S export sync class", "/api/job/${__P(jobSmall,)}/export", {"granularity": "class"}, "GET", None),
    ("S export start class", "/api/job/${__P(jobSmall,)}/export/start", None, "POST", '{"granularity":"class"}'),
]

S_COMPARE = [
    ("S compare small", "/api/session/compare", None, "POST",
     '{"leftType":"package","leftPath":"%s","rightPath":"%s","options":{"expandAll":true,"topK":12}}'
     % (SMALL_L, SMALL_R)),
]

# 200MB 任务的状态响应内嵌完整差异树，单独成计划，仅在基线（及能力上限专项）测。
S_READ_BIG = [
    ("S status big200", "/api/job/${__P(jobBig,)}/status", None, "GET", None),
]

S_AI = [
    ("S ai test", "/api/ai/test", None, "POST", None),
    ("S ai models", "/api/ai/models", None, "POST", None),
    ("S ai analyze", "/api/job/${__P(jobSmall,)}/ai-analyze", None, "POST", '{"category":"risk"}'),
    ("S ai classify", "/api/job/${__P(jobSmall,)}/ai-classify", None, "POST", '{}'),
    ("S ai estimate", "/api/job/${__P(jobSmall,)}/ai-estimate", None, "POST", '{"category":"risk"}'),
]

S_CONFIG = [
    ("S config GET", "/api/config", None, "GET", None),
    ("S config PUT", "/api/config", None, "PUT", '{"topK":12,"stageBTopK":15}'),
    ("S tasks running", "/api/tasks/running", None, "GET", None),
]

# 管理操作：真实业务里是「单个运维手点」，并发天然极低；且 diag-bundle 会写整包 zip、
# cleanup 会全量回收 bempdiff-* 临时文件（与并发的 bundle 生成互删 → 500），
# 故单独成组，阶梯只跑到 4 线程，采样超时收紧到 60s 以免拖垮整轮。
S_ADMINOPS = [
    ("S diag bundle", "/api/admin/diag-bundle", None, "GET", None, 60000),
    ("S admin cleanup", "/api/admin/cleanup", None, "POST", None, 60000),
]

# ---------------------------------------------------------------- Harness（18080）
H_READ = [
    ("H health", "/health", None, "GET", None),
    ("H parse small", "/api/parse", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true"}, "GET", None),
    ("H diff small", "/api/diff", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true"}, "GET", None),
    ("H decompile small", "/api/decompile/batch",
     {"old": SMALL_L, "new": SMALL_R, "expandAll": "true", "threads": "8", "topK": "12"}, "GET", None),
    ("H ai stageA", "/api/ai/stageA", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true", "topK": "12"}, "GET", None),
    ("H ai stageB", "/api/ai/stageB", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true", "topK": "8"}, "GET", None),
    ("H report", "/api/report", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true", "topK": "12"}, "GET", None),
    ("H export classes", "/api/export/classes", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true", "topK": "12"}, "GET", None),
    ("H export jars", "/api/export/jars", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true", "topK": "12"}, "GET", None),
    ("H export sources", "/api/export/sources", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true", "topK": "12"}, "GET", None),
    ("H audit write", "/api/audit", {"old": SMALL_L, "new": SMALL_R}, "GET", None),
    ("H audit query", "/api/audit/query", {"from": "2020-01-01", "to": "2030-01-01"}, "GET", None),
]

H_PARSE_DIFF = [
    ("H parse big200", "/api/parse", {"old": BIG_L, "new": BIG_R, "expandAll": "true"}, "GET", None),
    ("H diff big200", "/api/diff", {"old": BIG_L, "new": BIG_R, "expandAll": "true"}, "GET", None),
]

H_DECOMPILE = [
    ("H decompile lib", "/api/decompile/batch",
     {"old": LIB_L, "new": LIB_R, "expandAll": "true", "threads": "8", "topK": "60"}, "GET", None),
]

# Soak 读侧：只读，不创建新作业（否则会让下面引用的 jobSmall 快照被 sweep 释放）。
SOAK_SERVER = [
    ("SOAK status small", "/api/job/${__P(jobSmall,)}/status", None, "GET", None),
    ("SOAK report POST", "/api/job/${__P(jobSmall,)}/report", None, "POST", '{"ai":false}'),
    ("SOAK export list", "/api/export/list", None, "GET", None),
    ("SOAK config GET", "/api/config", None, "GET", None),
    ("SOAK tasks running", "/api/tasks/running", None, "GET", None),
    ("SOAK entry decompile", "/api/entry/decompile", {"jobId": "${__P(jobSmall,)}", "key": "${__P(keySmall,)}"}, "GET", None),
]

SOAK_WRITE = [
    # 每线程 5s 思考时间：Soak 要验的是「长期按真实节奏提交，队列/内存/句柄是否泄漏」，
    # 不是把提交端点打到数千 req/s（那属于压力阶梯的范畴）。
    ("S compare small", "/api/session/compare", None, "POST",
     '{"leftType":"package","leftPath":"%s","rightPath":"%s","options":{"expandAll":true,"topK":12}}'
     % (SMALL_L, SMALL_R), 600000, "${__P(thinkMs,5000)}"),
]

SOAK_HARNESS = [
    ("SOAK H parse small", "/api/parse", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true"}, "GET", None),
    ("SOAK H diff small", "/api/diff", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true"}, "GET", None),
    ("SOAK H report", "/api/report", {"old": SMALL_L, "new": SMALL_R, "expandAll": "true", "topK": "12"}, "GET", None),
]


def main():
    write("srv_session.jmx", plan("srv_session", [thread_group("load", S_SESSION)]))
    write("srv_archive.jmx", plan("srv_archive", [thread_group("load", S_ARCHIVE)]))
    write("srv_file.jmx", plan("srv_file", [thread_group("load", S_FILE)]))
    write("srv_export.jmx", plan("srv_export", [thread_group("load", S_EXPORT)]))
    write("srv_compare.jmx", plan("srv_compare", [thread_group("load", S_COMPARE)]))
    write("srv_read_big.jmx", plan("srv_read_big", [thread_group("load", S_READ_BIG)]))
    write("srv_ai.jmx", plan("srv_ai", [thread_group("load", S_AI)]))
    write("srv_config.jmx", plan("srv_config", [thread_group("load", S_CONFIG)]))
    write("srv_adminops.jmx", plan("srv_adminops", [thread_group("load", S_ADMINOPS)]))
    write("harness_read.jmx", plan("harness_read", [thread_group("load", H_READ)]))
    write("harness_parse_diff.jmx", plan("harness_parse_diff", [thread_group("load", H_PARSE_DIFF)]))
    write("harness_decompile.jmx", plan("harness_decompile", [thread_group("load", H_DECOMPILE)]))
    write("soak_server.jmx", plan("soak_server", [thread_group("soak", SOAK_SERVER)]))
    write("soak_harness.jmx", plan("soak_harness", [thread_group("soak", SOAK_HARNESS)]))
    write("soak_write.jmx", plan("soak_write", [thread_group("soak", SOAK_WRITE)]))
    print("ALL LOAD JMX DONE")


if __name__ == "__main__":
    main()
