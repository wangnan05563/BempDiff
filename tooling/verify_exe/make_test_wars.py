#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构造一对代表性 WAR 包（v1/v2），覆盖需求要求的各类文件：
   web.xml(XML) / properties / jsp / tag / css / js / png(二进制) / class / jar(lib)。
   用于验证 BempDiff 对 WAR 内所有文件类型的差异比对（分类 + 内容级 diff + 报告）。
"""
import os, zipfile, subprocess, sys

# 项目根由脚本位置推导（verify_exe → tooling → 项目根），避免目录迁移后失效
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
VER = os.path.join(ROOT, "tooling/verify_exe")
os.makedirs(VER, exist_ok=True)
JAVAC = os.path.join(ROOT, "bempdiff/toolchain/zulu21.52.15-ca-jdk21.0.12-win_x64/bin/javac.exe")

# 差异 lib jar 内部 class 的统一包路径（org/ 不在默认内部前缀 com//cn/ 中 → 视为第三方 L2 jar）
PKG = "org/difflib"

def compile_service(ver, body):
    src = os.path.join(VER, "Service.java")
    with open(src, "w", encoding="utf-8") as f:
        f.write('package com.example;\npublic class Service{\n' + body + '\n}\n')
    outd = os.path.join(VER, f"classes{ver}")
    os.makedirs(outd, exist_ok=True)
    subprocess.run([JAVAC, "-d", outd, src], check=True)
    # 找到 Service.class
    for dp, _, fs in os.walk(outd):
        if "Service.class" in fs:
            with open(os.path.join(dp, "Service.class"), "rb") as fh:
                return fh.read()
    raise FileNotFoundError("Service.class not produced")

def make_lib_jar(path, content):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        z.writestr("com/lib/Util.class", content)

def compile_lib_class(ver, body, classname):
    """编译一个差异 lib jar 内部的 class（真实 .class，可被 CFR 反编译），返回其字节。
    包名用 org.difflib（org/ 不在默认内部前缀 com//cn/ 中），确保其作为第三方 L2 jar 对待。"""
    pkg = PKG
    srcdir = os.path.join(VER, f"libsrc{ver}")
    os.makedirs(srcdir, exist_ok=True)
    srcpath = os.path.join(srcdir, classname + ".java")
    with open(srcpath, "w", encoding="utf-8") as f:
        f.write("package org.difflib;\npublic class " + classname + "{\n" + body + "\n}\n")
    outd = os.path.join(VER, f"libclasses{ver}")
    os.makedirs(outd, exist_ok=True)
    subprocess.run([JAVAC, "-d", outd, srcpath], check=True)
    with open(os.path.join(outd, pkg, classname + ".class"), "rb") as fh:
        return fh.read()

def make_diff_lib_jar(path, helper_v, extra_present):
    """构造差异依赖 JAR：Helper.class 在两版不同（修改）+ v2 额外含 Extra.class（新增）。
    内部 class 路径用 org/difflib（第三方包，不在默认内部前缀 com//cn/ 中）。"""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBundle-Version: 1.0.0\n")
        z.writestr(PKG + "/Helper.class", helper_v)
        if extra_present:
            z.writestr(PKG + "/Extra.class", EXTRA_CLASS)

def build_war(path, files):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBuilt-By: bempdiff-test\n")
        for name, data in files.items():
            z.writestr(name, data)

svc1 = compile_service(1, "  public int add(int a,int b){ return a+b; }")
svc2 = compile_service(2, "  public int add(int a,int b){ return a+b+1; }  // changed")

# 制作一个 lib jar（两版相同，验证 L2 未变）
make_lib_jar(os.path.join(VER, "_util.jar"), b"\xca\xfe\xba\xbe_lib_content_v1")

# 制作一个【差异】依赖 JAR（Req 6 验证）：v1/v2 均含，但 Helper.class 内容不同（修改），
# 且 v2 额外新增 Extra.class（新增）。该 jar 在 diff 中表现为 MODIFIED，内部含 1 修改 + 1 新增 class。
EXTRA_CLASS = compile_lib_class(2, "  public String tag(){ return \"v2-extra\"; }", "Extra")
helper_v1 = compile_lib_class(1, "  public String greet(){ return \"hello-v1\"; }\n  public int n(){ return 1; }", "Helper")
helper_v2 = compile_lib_class(2, "  public String greet(){ return \"hello-v2-changed\"; }\n  public int n(){ return 2; }", "Helper")
make_diff_lib_jar(os.path.join(VER, "_difflib_v1.jar"), helper_v1, extra_present=False)
make_diff_lib_jar(os.path.join(VER, "_difflib_v2.jar"), helper_v2, extra_present=True)

webxml_v1 = ('<?xml version="1.0" encoding="UTF-8"?>\n'
             '<web-app>\n  <servlet>\n    <servlet-name>demo</servlet-name>\n'
             '    <servlet-class>com.example.DemoServlet</servlet-class>\n  </servlet>\n'
             '  <welcome-file-list>\n    <welcome-file>index.jsp</welcome-file>\n  </welcome-file-list>\n'
             '</web-app>\n')
webxml_v2 = ('<?xml version="1.0" encoding="UTF-8"?>\n'
             '<web-app>\n  <servlet>\n    <servlet-name>demo</servlet-name>\n'
             '    <servlet-class>com.example.DemoServlet</servlet-class>\n'
             '    <load-on-startup>1</load-on-startup>\n  </servlet>\n'
             '  <filter>\n    <filter-name>auth</filter-name>\n  </filter>\n'
             '  <welcome-file-list>\n    <welcome-file>index.jsp</welcome-file>\n'
             '    <welcome-file>home.html</welcome-file>\n  </welcome-file-list>\n'
             '</web-app>\n')

cfg_v1 = "app.name=demo\napp.version=1.0\nfeature.flag=false\ntimeout=30\n"
cfg_v2 = "app.name=demo\napp.version=2.0\nfeature.flag=true\ntimeout=60\n"

jsp_v1 = ('<%@ page contentType="text/html;charset=UTF-8" %>\n'
          '<html><body><h1>Demo v1</h1><p>hello</p></body></html>\n')
jsp_v2 = ('<%@ page contentType="text/html;charset=UTF-8" %>\n'
          '<html><body><h1>Demo v2</h1><p>hello world</p><p>new line</p></body></html>\n')

tag_v1 = '<%@ tag pageEncoding="UTF-8" %><div class="box">v1</div>\n'
tag_v2 = '<%@ tag pageEncoding="UTF-8" %><div class="box">v2 updated</div><span>extra</span>\n'

css_v1 = "body{color:red;margin:0;padding:0;}"
css_v2 = "body{color:blue;margin:0;padding:8px;font-size:14px;}"

js_v1 = "function add(a,b){return a+b;}function sub(a,b){return a-b;}"
js_v2 = "function add(a,b){return a+b;}\nfunction sub(a,b){return a-b;}\nfunction mul(a,b){return a*b;}"

png_v1 = b"\x89PNG\r\n\x1a\n" + b"\x00" * 64
png_v2 = b"\x89PNG\r\n\x1a\n" + b"\xff" * 64

newjsp_v2 = '<%@ page contentType="text/html;charset=UTF-8" %><html><body>new page</body></html>\n'
newprop_v2 = "new.key=value\nnew.flag=true\n"

# v1
files_v1 = {
    "WEB-INF/web.xml": webxml_v1,
    "WEB-INF/classes/com/example/Service.class": svc1,
    "WEB-INF/classes/config.properties": cfg_v1,
    "index.jsp": jsp_v1,
    "tags/common.tag": tag_v1,
    "static/style.css": css_v1,
    "static/app.js": js_v1,
    "static/logo.png": png_v1,
    "WEB-INF/lib/util-lib.jar": open(os.path.join(VER, "_util.jar"), "rb").read(),
    "WEB-INF/lib/diff-lib.jar": open(os.path.join(VER, "_difflib_v1.jar"), "rb").read(),
}
# v2
files_v2 = {
    "WEB-INF/web.xml": webxml_v2,
    "WEB-INF/classes/com/example/Service.class": svc2,
    "WEB-INF/classes/config.properties": cfg_v2,
    "WEB-INF/classes/new.properties": newprop_v2,
    "index.jsp": jsp_v2,
    "tags/common.tag": tag_v2,
    "static/style.css": css_v2,
    "static/app.js": js_v2,
    "static/logo.png": png_v2,
    "new.jsp": newjsp_v2,
    "WEB-INF/lib/util-lib.jar": open(os.path.join(VER, "_util.jar"), "rb").read(),
    "WEB-INF/lib/diff-lib.jar": open(os.path.join(VER, "_difflib_v2.jar"), "rb").read(),
}

build_war(os.path.join(VER, "demo_v1.war"), files_v1)
build_war(os.path.join(VER, "demo_v2.war"), files_v2)
print("WARS_BUILT_OK")
print("v1:", os.path.getsize(os.path.join(VER, "demo_v1.war")))
print("v2:", os.path.getsize(os.path.join(VER, "demo_v2.war")))
