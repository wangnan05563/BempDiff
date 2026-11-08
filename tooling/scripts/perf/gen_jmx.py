#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BempDiff 性能测试 JMX 生成器（test-only）。

按场景生成多份 .jmx 到 tooling/jmeter/ 目录。运行方式：
  jmeter -n -t tooling/jmeter/<plan>.jmx -Jhost=localhost -Jport=18080 \
         -l tooling/jmeter/<plan>.jtl -e -o tooling/jmeter/<plan>-report

场景：
  parse_diff_fast        : /api/parse + /api/diff 打 big200（快速端点高并发）
  ai_report_export       : /api/ai/stageA + /api/report + /api/export/classes 打 small_real
  big500_sla             : /api/diff 打 big500（峰值 SLA 单次）
  decompile_scale_T{n}   : /api/decompile/batch 打 lib_v1/v2，内部线程= n（缩放曲线）
  decompile_conc_T{n}    : /api/decompile/batch 打 lib_v1/v2，外部并发= n（进程争用）
"""
import os, xml.sax.saxutils as X

OUT = "tooling/jmeter"
os.makedirs(OUT, exist_ok=True)

FIX = "bempdiff/fixtures"   # 相对项目根；JMeter 启动时 cwd 必须是项目根
LIB1 = "bempdiff/lib_v1.jar"
LIB2 = "bempdiff/lib_v2.jar"
BIG200_1 = FIX + "/big200_v1.jar"; BIG200_2 = FIX + "/big200_v2.jar"
BIG500_1 = FIX + "/big500_v1.jar"; BIG500_2 = FIX + "/big500_v2.jar"
SMALL_1 = FIX + "/small_real_v1.jar"; SMALL_2 = FIX + "/small_real_v2.jar"


def sampler(name, path, params):
    args = "".join(
        '<elementProp name="%s" elementType="HTTPArgument">'
        '<boolProp name="HTTPArgument.always_encode">true</boolProp>'
        '<stringProp name="Argument.name">%s</stringProp>'
        '<stringProp name="Argument.value">%s</stringProp>'
        '<stringProp name="Argument.metadata">=</stringProp>'
        '</elementProp>' % (k, X.escape(k), X.escape(str(v)))
        for k, v in params.items()
    )
    return (
        '<HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" '
        'testname="%s" enabled="true">'
        '<elementProp name="HTTPsampler.Arguments" elementType="Arguments" '
        'guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">'
        '<collectionProp name="Arguments.arguments">%s</collectionProp>'
        '</elementProp>'
        '<stringProp name="HTTPSampler.domain">${__P(host,localhost)}</stringProp>'
        '<stringProp name="HTTPSampler.port">${__P(port,18080)}</stringProp>'
        '<stringProp name="HTTPSampler.protocol">http</stringProp>'
        '<stringProp name="HTTPSampler.path">%s</stringProp>'
        '<stringProp name="HTTPSampler.method">GET</stringProp>'
        '<boolProp name="HTTPSampler.follow_redirects">true</boolProp>'
        '<boolProp name="HTTPSampler.use_keepalive">true</boolProp>'
        '<boolProp name="HTTPSampler.postBodyRaw">false</boolProp>'
        '</HTTPSamplerProxy>' % (X.escape(name), args, X.escape(path))
    )


def thread_group(name, threads, ramp, duration, samplers):
    s = "".join(sampler(n, p, pr) + "<hashTree/>" for n, p, pr in samplers)
    return (
        '<ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="%s" enabled="true">'
        '<stringProp name="ThreadGroup.on_sample_error">continue</stringProp>'
        '<elementProp name="ThreadGroup.main_controller" elementType="LoopController" '
        'guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">'
        '<boolProp name="LoopController.continue_forever">false</boolProp>'
        '<stringProp name="LoopController.loops">-1</stringProp>'
        '</elementProp>'
        '<stringProp name="ThreadGroup.num_threads">%d</stringProp>'
        '<stringProp name="ThreadGroup.ramp_time">%d</stringProp>'
        '<boolProp name="ThreadGroup.scheduler">true</boolProp>'
        '<stringProp name="ThreadGroup.duration">%d</stringProp>'
        '<stringProp name="ThreadGroup.delay"></stringProp>'
        '<boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>'
        '</ThreadGroup><hashTree>%s'
        '<ResultCollector guiclass="SummaryReport" testclass="ResultCollector" testname="Summary Report" enabled="true">'
        '<boolProp name="ResultCollector.error_logging">false</boolProp>'
        '<stringProp name="filename"></stringProp>'
        '</ResultCollector><hashTree/>'
        '</hashTree>' % (X.escape(name), threads, ramp, duration, s)
    )


def plan(testname, groups):
    g = "".join(groups)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">\n'
        '<hashTree>\n'
        '<TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="%s" enabled="true">'
        '<stringProp name="TestPlan.comments"></stringProp>'
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
    p = os.path.join(OUT, name)
    with open(p, "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote", p)


# 1) 快速端点高并发：parse / diff 打 big200
pd = [
    ("parse_big200", "/api/parse", {"old": BIG200_1, "new": BIG200_2, "expandAll": "true"}),
    ("diff_big200", "/api/diff", {"old": BIG200_1, "new": BIG200_2, "expandAll": "true"}),
]
write("parse_diff_fast.jmx", plan("parse_diff_fast", [thread_group("load50", 50, 10, 60, pd)]))
write("parse_diff_fast_100.jmx", plan("parse_diff_fast_100", [thread_group("load100", 100, 20, 60, pd)]))

# 2) ai / report / export 打 small_real（含小批量 decompile）
ar = [
    ("ai_stageA", "/api/ai/stageA", {"old": SMALL_1, "new": SMALL_2, "expandAll": "true", "threads": "4", "topK": "8"}),
    ("report", "/api/report", {"old": SMALL_1, "new": SMALL_2, "expandAll": "true", "threads": "4", "topK": "8"}),
    ("export_classes", "/api/export/classes", {"old": SMALL_1, "new": SMALL_2, "expandAll": "true", "threads": "4", "topK": "8"}),
]
write("ai_report_export.jmx", plan("ai_report_export", [thread_group("load10", 10, 5, 120, ar)]))

# 3) big500 SLA 单次
b5 = [("diff_big500", "/api/diff", {"old": BIG500_1, "new": BIG500_2, "expandAll": "true"})]
write("big500_sla.jmx", plan("big500_sla", [thread_group("single", 1, 1, 30, b5)]))

# 4) decompile 内部线程缩放曲线（topK=80）
for n in (1, 2, 4, 8, 16):
    d = [("decompile", "/api/decompile/batch",
          {"old": LIB1, "new": LIB2, "expandAll": "true", "threads": str(n), "topK": "80"})]
    write("decompile_scale_T%d.jmx" % n, plan("decompile_scale_T%d" % n, [thread_group("scale", 1, 1, 180, d)]))

# 5) decompile 外部并发（topK=60）
for n in (1, 2, 4, 8):
    d = [("decompile", "/api/decompile/batch",
          {"old": LIB1, "new": LIB2, "expandAll": "true", "threads": "8", "topK": "60"})]
    write("decompile_conc_T%d.jmx" % n, plan("decompile_conc_T%d" % n, [thread_group("conc", n, n, 180, d)]))

print("ALL JMX DONE")
