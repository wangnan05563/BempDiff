#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构造用于验证 P1-1(单jar失败隔离) 与 P1-2(全局 Top-K 预算) 的 WAR 对。
- libA.jar / libB.jar：各自含 2 个真实可反编译 class（v1/v2 均修改）→ 共 4 个变更 class。
- bad.jar：非 zip 的非法字节（v1/v2 不同）→ 在 DiffResult 中为 MODIFIED，但 LibJarDiff 读取/枚举将失败。
配合 --top-k 3 运行 diff-jars：
  - 全局 Top-K：libA 最多 2 + libB 最多 1（预算耗尽），合计 3（而非每 jar 各自 2 = 4）。
  - 失败隔离：bad.jar 应显示「[分析失败]」且不影响其余 JAR 与报告。
"""
import os, zipfile, subprocess, sys

# 项目根由脚本位置推导（verify_exe → tooling → 项目根），避免目录迁移后失效
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VER = os.path.join(ROOT, "tooling/verify_exe")
JAVAC = os.path.join(ROOT, "bempdiff/toolchain/zulu21.52.15-ca-jdk21.0.12-win_x64/bin/javac.exe")
PKG = "org/difflib"

def compile_class(ver, body, classname):
    srcdir = os.path.join(VER, f"ctsrc{ver}")
    os.makedirs(srcdir, exist_ok=True)
    src = os.path.join(srcdir, classname + ".java")
    with open(src, "w", encoding="utf-8") as f:
        # 注意：源码包声明用点号；PKG(斜杠) 仅用于 zip 条目路径
        f.write("package org.difflib;\npublic class " + classname + "{\n" + body + "\n}\n")
    outd = os.path.join(VER, f"ctcls{ver}")
    os.makedirs(outd, exist_ok=True)
    subprocess.run([JAVAC, "-d", outd, src], check=True)
    with open(os.path.join(outd, PKG, classname + ".class"), "rb") as fh:
        return fh.read()

def make_jar(path, entries):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        for name, data in entries.items():
            z.writestr(name, data)

# 真实 class：libA（A1/A2），libB（B1/B2），v1->v2 修改
a1v1 = compile_class(1, "  public int x(){ return 1; }", "A1")
a1v2 = compile_class(2, "  public int x(){ return 100; }", "A1")
a2v1 = compile_class(1, "  public int y(){ return 2; }", "A2")
a2v2 = compile_class(2, "  public int y(){ return 200; }", "A2")
b1v1 = compile_class(1, "  public int p(){ return 3; }", "B1")
b1v2 = compile_class(2, "  public int p(){ return 300; }", "B1")
b2v1 = compile_class(1, "  public int q(){ return 4; }", "B2")
b2v2 = compile_class(2, "  public int q(){ return 400; }", "B2")

# 非法 jar：非 zip 字节，v1/v2 不同（触发 MODIFIED + 枚举失败）
bad_v1 = b"\x00\x01\x02not-a-real-jar" + b"A" * 20
bad_v2 = b"\x00\x01\x02not-a-real-jar" + b"B" * 20

make_jar(os.path.join(VER, "_ctA_v1.jar"), {PKG + "/A1.class": a1v1, PKG + "/A2.class": a2v1})
make_jar(os.path.join(VER, "_ctA_v2.jar"), {PKG + "/A1.class": a1v2, PKG + "/A2.class": a2v2})
make_jar(os.path.join(VER, "_ctB_v1.jar"), {PKG + "/B1.class": b1v1, PKG + "/B2.class": b2v1})
make_jar(os.path.join(VER, "_ctB_v2.jar"), {PKG + "/B1.class": b1v2, PKG + "/B2.class": b2v2})

def build_war(path, jars):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        for name, data in jars.items():
            z.writestr(name, data)

v1 = {
    "WEB-INF/lib/libA.jar": open(os.path.join(VER, "_ctA_v1.jar"), "rb").read(),
    "WEB-INF/lib/libB.jar": open(os.path.join(VER, "_ctB_v1.jar"), "rb").read(),
    "WEB-INF/lib/bad.jar": bad_v1,
}
v2 = {
    "WEB-INF/lib/libA.jar": open(os.path.join(VER, "_ctA_v2.jar"), "rb").read(),
    "WEB-INF/lib/libB.jar": open(os.path.join(VER, "_ctB_v2.jar"), "rb").read(),
    "WEB-INF/lib/bad.jar": bad_v2,
}
build_war(os.path.join(VER, "corrupt_v1.war"), v1)
build_war(os.path.join(VER, "corrupt_v2.war"), v2)
print("CORRUPT_WARS_BUILT_OK")
