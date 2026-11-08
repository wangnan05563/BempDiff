#!/usr/bin/env python3
# 递归解包活体冒烟：真实启动 Main server，走 compare->children->decompile 全链路，
# 覆盖 顶层归档展开 / 内部 class 反编译 / 内部文本 diff / 嵌套 zip 递归展开。
import io, os, socket, subprocess, sys, time, urllib.request, urllib.error, zipfile, json, shutil, tempfile

# 脚本所在 tests_smoke → java_core → bempdiff → 项目根
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
JAVA = os.path.join(ROOT, "bempdiff", "toolchain", "zulu21.52.15-ca-jdk21.0.12-win_x64", "bin", "java.exe")
JAVAC = os.path.join(ROOT, "bempdiff", "toolchain", "zulu21.52.15-ca-jdk21.0.12-win_x64", "bin", "javac.exe")
CFR = os.path.join(ROOT, "bempdiff", "cfr.jar")
CORE_OUT = os.path.join(ROOT, "bempdiff", "java_core", "out")
MAIN = "com.bempdiff.Main"

def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p

def make_zip_bytes(entries: dict) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            z.writestr(name, data)
    return buf.getvalue()

def write_zip(path, entries):
    with open(path, "wb") as f:
        f.write(make_zip_bytes(entries))

def compile_real_class(tmp, fqcn, src):
    """用 Zulu21 javac 把源码编译成真实 .class 字节（供反编译夹具）。"""
    pkg_dir = os.path.join(tmp, "src", *fqcn.split(".")[:-1])
    os.makedirs(pkg_dir, exist_ok=True)
    src_file = os.path.join(pkg_dir, fqcn.split(".")[-1] + ".java")
    with open(src_file, "w", encoding="utf-8") as f:
        f.write(src)
    out_dir = os.path.join(tmp, "clsout")
    os.makedirs(out_dir, exist_ok=True)
    subprocess.run([JAVAC, "-d", out_dir, src_file], check=True,
                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    cls = os.path.join(out_dir, *fqcn.split(".")) + ".class"
    with open(cls, "rb") as f:
        return f.read()

def main():
    tmp = tempfile.mkdtemp(prefix="bd-rec-smoke-")
    old_dir = os.path.join(tmp, "old"); new_dir = os.path.join(tmp, "new")
    os.makedirs(old_dir); os.makedirs(new_dir)

    # 真实 .class 字节（让 Decompiler 能真正反编译）：A 改一处、B 改一处
    A_SRC_V1 = "package com.demo;\npublic class A {\n  private String name = \"old\";\n  public int add(int a,int b){return a+b;}\n}\n"
    A_SRC_V2 = "package com.demo;\npublic class A {\n  private String name = \"new\";\n  public int add(int a,int b){return a+b+1;}\n  public void extra(){}\n}\n"
    B_SRC_V1 = "package com.demo;\npublic class B {\n  public int v = 1;\n}\n"
    B_SRC_V2 = "package com.demo;\npublic class B {\n  public int v = 2;\n}\n"
    a_old = compile_real_class(tmp, "com.demo.A", A_SRC_V1)
    a_new = compile_real_class(tmp, "com.demo.A", A_SRC_V2)
    b_old = compile_real_class(tmp, "com.demo.B", B_SRC_V1)
    b_new = compile_real_class(tmp, "com.demo.B", B_SRC_V2)

    # 顶层 app.zip：内部含真实 A.class、conf.txt、lib/nested.zip(含真实 B.class)
    nested_old = make_zip_bytes({"B.class": b_old})
    nested_new = make_zip_bytes({"B.class": b_new})
    app_old = {
        "WEB-INF/classes/com/demo/A.class": a_old,
        "conf/app.txt": b"name=old\nport=8080\n",
        "lib/nested.zip": nested_old,
    }
    app_new = {
        "WEB-INF/classes/com/demo/A.class": a_new,
        "conf/app.txt": b"name=new\nport=9090\n",
        "lib/nested.zip": nested_new,
    }
    write_zip(os.path.join(old_dir, "app.zip"), app_old)
    write_zip(os.path.join(new_dir, "app.zip"), app_new)
    # 顶层一个普通被改文件，验证非归档路径不受影响
    with open(os.path.join(old_dir, "readme.txt"), "w") as f: f.write("v1")
    with open(os.path.join(new_dir, "readme.txt"), "w") as f: f.write("v2-changed")

    port = free_port()
    cp = CORE_OUT + ";" + CFR
    # 关键：server 的临时目录用完整长路径，避免 8.3 短路径（如 C:\PYFIX_~4）导致反编译器读临时 .class 失败
    temp_dir = os.path.join(tmp, "srvtmp")
    os.makedirs(temp_dir, exist_ok=True)
    env = dict(os.environ)
    env["TEMP"] = temp_dir
    env["TMP"] = temp_dir
    env["TMPDIR"] = temp_dir
    proc = subprocess.Popen(
        [JAVA, "-cp", cp, "-Dfile.encoding=UTF-8", MAIN, "server", "--port", str(port)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env,
    )
    base = f"http://127.0.0.1:{port}"

    def req(method, path, data=None, timeout=30):
        body = json.dumps(data).encode() if data is not None else None
        r = urllib.request.Request(base + path, data=body, method=method,
                                    headers={"Content-Type": "application/json"} if body else {})
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")

    ok = True
    try:
        # 等待 server 起来
        for _ in range(50):
            try:
                req("GET", "/api/config"); break
            except Exception:
                time.sleep(0.2)
        else:
            raise RuntimeError("server 未启动")

        # 1) 提交 folder 比对
        st, body = req("POST", "/api/session/compare",
                        {"leftType": "folder", "leftPath": old_dir, "rightPath": new_dir,
                         "options": {"expandAll": False}})
        assert st == 200, f"compare 失败 {st}: {body}"
        job_id = json.loads(body)["jobId"]
        print(f"[ok] compare 提交 jobId={job_id}")

        # 2) 轮询到 DONE
        for _ in range(200):
            st, body = req("GET", f"/api/job/{job_id}/status")
            j = json.loads(body)
            if j.get("status") == "DONE":
                break
            if j.get("status") == "ERROR":
                raise RuntimeError("比对 ERROR: " + j.get("error", ""))
            time.sleep(0.1)
        else:
            raise RuntimeError("比对超时未 DONE")
        print("[ok] 比对 DONE")

        # 3) 顶层 app.zip 展开 children
        st, body = req("GET", f"/api/entry/children?jobId={job_id}&key=app.zip")
        assert st == 200, f"children 失败 {st}: {body}"
        kids = json.loads(body)
        keys = {k["key"]: k for k in kids}
        assert "app.zip!/WEB-INF/classes/com/demo/A.class" in keys, "缺少 A.class 子节点"
        assert "app.zip!/conf/app.txt" in keys, "缺少 conf/app.txt 子节点"
        assert "app.zip!/lib/nested.zip" in keys, "缺少 nested.zip 子节点"
        assert keys["app.zip!/WEB-INF/classes/com/demo/A.class"]["status"] == "MODIFIED"
        assert keys["app.zip!/lib/nested.zip"]["expandable"] is True
        print(f"[ok] children 返回 {len(kids)} 个内部条目，状态正确")

        # 4) 内部 class 反编译
        st, body = req("GET", f"/api/entry/decompile?jobId={job_id}&key=app.zip!/WEB-INF/classes/com/demo/A.class")
        assert st == 200, f"decompile class 失败 {st}: {body}"
        d = json.loads(body)
        assert d["ok"], f"内部 class 反编译不 ok: {d}"
        assert d["diffText"], "内部 class 应返回 diff"
        print("[ok] 内部 class 反编译 ok")

        # 5) 内部文本 diff
        st, body = req("GET", f"/api/entry/decompile?jobId={job_id}&key=app.zip!/conf/app.txt")
        assert st == 200, f"decompile text 失败 {st}: {body}"
        d = json.loads(body)
        assert d["ok"], f"内部文本 diff 不 ok: {d}"
        print("[ok] 内部文本 diff ok")

        # 6) 嵌套 zip 递归展开
        st, body = req("GET", f"/api/entry/children?jobId={job_id}&key=app.zip!/lib/nested.zip")
        assert st == 200, f"嵌套 children 失败 {st}: {body}"
        nkids = json.loads(body)
        nkeys = {k["key"]: k for k in nkids}
        assert "app.zip!/lib/nested.zip!/B.class" in nkeys, "嵌套 zip 内应含 B.class"
        assert nkeys["app.zip!/lib/nested.zip!/B.class"]["status"] == "MODIFIED"
        print(f"[ok] 嵌套 zip 递归展开，含 B.class(MODIFIED)")

        # 7) 嵌套 class 反编译
        st, body = req("GET", f"/api/entry/decompile?jobId={job_id}&key=app.zip!/lib/nested.zip!/B.class")
        assert st == 200, f"嵌套 decompile 失败 {st}: {body}"
        d = json.loads(body)
        assert d["ok"], f"嵌套 class 反编译不 ok: {d}"
        print("[ok] 嵌套 class 反编译 ok")

        # 8) 3 层嵌套：app.zip -> lib/bundle.zip -> deep/inner.zip -> data.txt（含目录结构/逐层状态）
        inner_old = make_zip_bytes({"data.txt": b"v1", "keep.txt": b"same"})
        inner_new = make_zip_bytes({"data.txt": b"v2", "keep.txt": b"same", "new.txt": b"n"})
        mid_old = make_zip_bytes({"deep/inner.zip": inner_old, "info.txt": b"i-old"})
        mid_new = make_zip_bytes({"deep/inner.zip": inner_new, "info.txt": b"i-old"})
        deep_old = make_zip_bytes({"lib/bundle.zip": mid_old})
        deep_new = make_zip_bytes({"lib/bundle.zip": mid_new})
        deep_old_dir = os.path.join(tmp, "deepold"); deep_new_dir = os.path.join(tmp, "deepnew")
        os.makedirs(deep_old_dir); os.makedirs(deep_new_dir)
        write_zip(os.path.join(deep_old_dir, "app.zip"), {"lib/bundle.zip": mid_old, "lib/other.txt": b"x"})
        write_zip(os.path.join(deep_new_dir, "app.zip"), {"lib/bundle.zip": mid_new, "lib/other.txt": b"x"})

        st, body = req("POST", "/api/session/compare",
                        {"leftType": "folder", "leftPath": deep_old_dir, "rightPath": deep_new_dir,
                         "options": {"expandAll": False}})
        assert st == 200, f"deep compare 失败 {st}: {body}"
        job2 = json.loads(body)["jobId"]
        for _ in range(200):
            st, body = req("GET", f"/api/job/{job2}/status")
            j = json.loads(body)
            if j.get("status") == "DONE": break
            if j.get("status") == "ERROR": raise RuntimeError("deep 比对 ERROR: " + j.get("error", ""))
            time.sleep(0.1)
        else:
            raise RuntimeError("deep 比对超时")

        # L2 展开 bundle.zip
        st, body = req("GET", f"/api/entry/children?jobId={job2}&key=app.zip!/lib/bundle.zip")
        assert st == 200, f"deep L2 失败 {st}: {body}"
        l2 = {k["key"]: k for k in json.loads(body)}
        assert "app.zip!/lib/bundle.zip!/deep/inner.zip" in l2, "L2 应含 deep/inner.zip"
        assert l2["app.zip!/lib/bundle.zip!/deep/inner.zip"]["status"] == "MODIFIED"
        assert l2["app.zip!/lib/bundle.zip!/deep/inner.zip"]["expandable"] is True
        assert "app.zip!/lib/bundle.zip!/deep/" in l2, "L2 应含目录结构节点 deep/"

        # L3 展开 inner.zip，最内层文件状态正确
        st, body = req("GET", f"/api/entry/children?jobId={job2}&key=app.zip!/lib/bundle.zip!/deep/inner.zip")
        assert st == 200, f"deep L3 失败 {st}: {body}"
        l3 = {k["key"]: k for k in json.loads(body)}
        assert l3["app.zip!/lib/bundle.zip!/deep/inner.zip!/data.txt"]["status"] == "MODIFIED", "data.txt 应为 MODIFIED"
        assert l3["app.zip!/lib/bundle.zip!/deep/inner.zip!/keep.txt"]["status"] == "UNCHANGED", "keep.txt 应为 UNCHANGED"
        assert l3["app.zip!/lib/bundle.zip!/deep/inner.zip!/new.txt"]["status"] == "ADDED", "new.txt 应为 ADDED"
        print("[ok] 3 层嵌套逐层递归展开，最内层 ADDED/MODIFIED/UNCHANGED 状态正确")

        # 9) 自动递归解包端点：一次返回完整嵌套树
        st, body = req("GET", f"/api/entry/recursive?jobId={job2}&key=app.zip")
        assert st == 200, f"recursive 失败 {st}: {body}"
        tree = json.loads(body)

        def find_node(nodes, key):
            for n in (nodes or []):
                if n.get("key") == key: return n
                hit = find_node(n.get("children"), key)
                if hit: return hit
            return None

        assert find_node(tree.get("children"), "app.zip!/lib/bundle.zip") is not None, "递归树应含 bundle.zip"
        deep_node = find_node(tree.get("children"), "app.zip!/lib/bundle.zip!/deep/inner.zip")
        assert deep_node is not None, "递归树应穿透到 deep/inner.zip"
        data_node = find_node(deep_node.get("children"), "app.zip!/lib/bundle.zip!/deep/inner.zip!/data.txt")
        assert data_node is not None and data_node["status"] == "MODIFIED", "递归树最内层 data.txt 应 MODIFIED"
        print("[ok] /api/entry/recursive 一次返回完整嵌套差异树")

        # 10) 包对比模式（直接比对两个 .zip）：修复前嵌套归档无法解包（NoSuchFileException）
        pkg_old = os.path.join(tmp, "pkg_old.zip"); pkg_new = os.path.join(tmp, "pkg_new.zip")
        write_zip(pkg_old, {"lib/bundle.zip": mid_old, "README.txt": b"readme"})
        write_zip(pkg_new, {"lib/bundle.zip": mid_new, "README.txt": b"readme"})
        st, body = req("POST", "/api/session/compare",
                        {"leftType": "package", "leftPath": pkg_old, "rightPath": pkg_new,
                         "options": {"expandAll": False}})
        assert st == 200, f"pkg compare 失败 {st}: {body}"
        job3 = json.loads(body)["jobId"]
        for _ in range(200):
            st, body = req("GET", f"/api/job/{job3}/status")
            j = json.loads(body)
            if j.get("status") == "DONE": break
            if j.get("status") == "ERROR": raise RuntimeError("pkg 比对 ERROR: " + j.get("error", ""))
            time.sleep(0.1)
        else:
            raise RuntimeError("pkg 比对超时")

        # 包模式下嵌套 bundle.zip 顶层 key 即条目名（修复点）
        st, body = req("GET", f"/api/entry/children?jobId={job3}&key=lib/bundle.zip")
        assert st == 200, f"pkg 嵌套 children 失败 {st}: {body}"
        pk = {k["key"]: k for k in json.loads(body)}
        assert "lib/bundle.zip!/deep/inner.zip" in pk, "包模式嵌套展开应含 deep/inner.zip（修复点）"
        assert pk["lib/bundle.zip!/deep/inner.zip"]["status"] == "MODIFIED"
        st, body = req("GET", f"/api/entry/children?jobId={job3}&key=lib/bundle.zip!/deep/inner.zip")
        assert st == 200, f"pkg 深层 children 失败 {st}: {body}"
        pk3 = {k["key"]: k for k in json.loads(body)}
        assert pk3["lib/bundle.zip!/deep/inner.zip!/data.txt"]["status"] == "MODIFIED", "包模式最内层 data.txt 应 MODIFIED"
        print("[ok] 包对比模式嵌套 zip 逐层递归展开（修复 NoSuchFileException）")

        print("\n=== 递归解包活体冒烟全部通过 ===")
    except AssertionError as e:
        ok = False
        print("断言失败:", e)
    except Exception as e:
        ok = False
        print("异常:", repr(e))
    finally:
        try: proc.terminate()
        except Exception: pass
        try:
            shutil.rmtree(tmp, ignore_errors=True)
        except Exception: pass
    sys.exit(0 if ok else 1)

if __name__ == "__main__":
    main()
