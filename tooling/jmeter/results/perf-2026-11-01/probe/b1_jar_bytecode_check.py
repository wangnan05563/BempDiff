"""§8e：核对发布产物 jar 内的 BempServer.class 确实带上 B1 的四处改动。

不依赖「jar 构建时间晚于源码 mtime」这条间接证据：直接从 jar 取字节码反编译，
逐条断言四处改动在常量池/调用序列上的判据。任一判据缺失即 exit 1。

用法（工具链不在 PATH，用仓内 Zulu21）:
    python -X utf8 tooling/jmeter/results/perf-2026-11-01/probe/b1_jar_bytecode_check.py
"""
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__),
                                    "..", "..", "..", "..", ".."))
JAR = os.path.join(ROOT, "bempdiff", "dist_input", "app", "bempdiff.jar")
TOK = os.path.join(ROOT, "bempdiff", "toolchain",
                   "zulu21.52.15-ca-jdk21.0.12-win_x64", "bin")
WORK = os.path.join(os.environ.get("TEMP", "."), "b1_jar_bytecode_check")


def sh(args):
    return subprocess.run(args, cwd=WORK, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="ignore")


def main():
    for exe in ("jar.exe", "javap.exe"):
        p = os.path.join(TOK, exe)
        if not os.path.exists(p):
            print("[FAIL] 工具链缺失: " + p)
            return 2
    if not os.path.exists(JAR):
        print("[FAIL] 发布产物缺失: " + JAR)
        return 2

    os.makedirs(WORK, exist_ok=True)
    rel = "com/bempdiff/server/BempServer.class"
    target = os.path.join(WORK, *rel.split("/"))
    if os.path.exists(target):
        os.remove(target)
    r = sh([os.path.join(TOK, "jar.exe"), "xf", JAR, rel.replace("/", os.sep)])
    if r.returncode != 0 or not os.path.exists(target):
        print("[FAIL] jar 解包失败 rc=%s\n%s" % (r.returncode, r.stdout))
        return 1

    dis = sh([os.path.join(TOK, "javap.exe"), "-p", "-c", target])
    if dis.returncode != 0:
        print("[FAIL] javap 反编译失败 rc=%s\n%s" % (dis.returncode, dis.stdout))
        return 1
    text = dis.stdout

    checks = [
        # ① sendFile：先开 FileChannel 再取长度，之后才发响应头
        ("sendFile FileChannel.open(READ)",
         r"FileChannel\.open:\(Ljava/nio/file/Path;\[Ljava/nio/file/OpenOption;\)"),
        ("sendFile FileChannel.size() 取代 Files.size()",
         r"FileChannel\.size:\(\)J"),
        ("sendFile Channels.newInputStream",
         r"Channels\.newInputStream:\(Ljava/nio/channels/ReadableByteChannel;\)"),
        # ② 诊断包写专属子目录（非 bempdiff-* 前缀）
        ("handleDiagBundle 使用 \"bmddiag-\" 前缀", r"String bmddiag-"),
        ("handleDiagBundle 用 createTempDirectory(Path,String,...)",
         r"Files\.createTempDirectory:\(Ljava/nio/file/Path;Ljava/lang/String;\[Ljava/nio/"
         r"file/attribute/FileAttribute;\)"),
        # ③ 清理 30s 新鲜度守卫
        ("cleanupSystemTemp 比较 lastModifiedMs 与 30000", r"long 30000l"),
    ]

    failed = []
    for name, pat in checks:
        hit = len(re.findall(pat, text))
        print("%-58s hits=%d %s" % (name, hit, "OK" if hit else "FAIL"))
        if not hit:
            failed.append(name)

    # 顺序断言：sendFile 体内 size() 必须早于 sendResponseHeaders，且都在 newInputStream 前
    m = re.search(r"void sendFile\(com\.sun\.net\.httpserver\.HttpExchange, int, "
                  r"java\.lang\.String, java\.nio\.file\.Path\)(.{0,4000})",
                  text, re.S)
    if not m:
        failed.append("sendFile 方法体未定位")
    else:
        body = m.group(1)

        def pos(pat):
            i = re.search(pat, body)
            return i.start() if i else -1

        p_open = pos(r"FileChannel\.open:")
        p_size = pos(r"FileChannel\.size:\(\)J")
        p_hdr = pos(r"sendResponseHeaders:\(IJ\)V")
        p_in = pos(r"Channels\.newInputStream:")
        order_ok = -1 < p_open < p_size < p_hdr < p_in
        print("%-58s open=%d size=%d hdr=%d in=%d %s" %
              ("sendFile 顺序 open<size<hdr<newInputStream",
               p_open, p_size, p_hdr, p_in, "OK" if order_ok else "FAIL"))
        if not order_ok:
            failed.append("sendFile 顺序")

    print("\n[b1_jar_bytecode_check] " + ("PASS" if not failed else "FAIL: " + "; ".join(failed)))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
