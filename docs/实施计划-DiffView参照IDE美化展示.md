# 代码文件对比栏参照 IDE 美化展示 实施计划

> **For agentic workers：** REQUIRED SUB-SKILL：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐步实施本计划。步骤用 `- [ ]` 复选框跟踪。

**Goal（目标）：** 参照 IDE（VS Code / JetBrains Git 对比）样式美化 BempDiff 的代码文件对比栏（`DiffView.vue`），把现有 70% 接近 IDE 的细节补齐至 ≥95% 视觉一致：含差异色条、整行加色、行内色块反色、filebar 磨砂玻璃、当前行主色描边、**git 短哈希标题**、**rep 行中央虚线分隔**等。本期确认范围 = **全套 IDE 化（含 git 短哈希）**。

**Architecture（架构）：** 全部改造局限于渲染层 + 一处后端数据补充。架构上沿用既有 `bempdiff/webui/src/components/DiffView.vue`（单文件组件）。新增改动：

1. **后端数据层**：`DecompiledUnit` 增加 `oldHash` / `newHash`（短哈希 7 位，类比 git `git rev-parse --short`），`BempServer.handleEntryDecompile` 在响应 JSON 中透出。来源：每侧 `decompileOne()` 入口前对原始 class 字节做一次 SHA-1 → 取前 7 hex 字符；非 class / 文本类用 `path + size + mtime` 计算 7 位短哈希；空文件用 `0000000`。
2. **前端模板层**：`DiffView.vue` 的 `filebar` 区域增加 git 短哈希标签 + rep 行右侧 `border-left: 1px dashed`；不改 `rows`/`uniRows` 数据结构。
3. **前端样式层**（约 120–150 行 CSS 改造）：差异色条、整行加色、行内色块反色、磨砂玻璃、当前行主色描边、折叠占位条等。

**Tech Stack（技术栈）：** Java 17（`java.security.MessageDigest` + 文件字节流）、Vue3 SFC（`<script setup>` + 局部 CSS）、Bootstrap 5 工具类、Bi Icons（`bi-filetype-*`、`bi-circle-fill` 等）。无新增三方依赖。

---

## 设计决策（先约定，避免实施时反复）

1. **git 短哈希口径**：固定 7 位十六进制（`SHA-1` 前 7 位），与 git `--short=7` 默认一致；与后端 `cacheKey` 计算解耦，独立存 `oldHash/newHash` 两个字段，不影响缓存命中。
2. **多源兜底**：
   - 纯 class（`FileClass.CLASS`）：对原始字节做 `SHA-1` → 7 hex。
   - 文本类（`FileClass.CONFIG` / `STATIC` / `JS` / `HTML` / `CSS` / `JSP`）：对 `path + size + lastModified` 拼串做 `SHA-1` → 7 hex。
   - 归档/Office 等非可读：对 `path + size` 拼串做 `SHA-1` → 7 hex（mtime 在 jar 内通常丢失，故只取 path+size）。
   - 一侧缺失（仅 ADDED / 仅 DELETED）：缺失侧哈希填 `0000000`，新增侧正常计算。
3. **不破坏既有协议**：在 `DecompiledUnit` 现有字段后**追加** `oldHash` / `newHash`，构造器用新增 9 参版本；旧调用点（`Main.java` 内多处）改为调新构造器。`Json.write` 由 JSON 字段顺序保证新字段落在尾部，前端 `getDecompile` 解析做向后兼容（缺字段时显示 `0000000`）。
4. **filebar 短哈希显示**：
   - 标题：`文件名` + `(` + `oldHash` `↔` `newHash` + `)`。
   - 颜色：旧包 `text-secondary`、新包 `text-primary`；中间 `↔` 灰色。
   - 折叠后用 `title` 悬浮提示「提交：oldHash → newHash」便于回溯。
5. **rep 行中央虚线**：仅在「不换行 split 模式 + 改动类型为 rep」生效；wrap 单栏模式不显示（避免视觉割裂），Git unified 模式也不显示（unified 自身已有 `+/−` 前缀）。
6. **差异色条 = gutter 列底色**：现有 `gt` 列宽 `1.15rem` 已有图标/底色；本轮把它升级为"整列细色条 + 图标居中"，并把行号列左缘加 `box-shadow: inset 3px 0 0 <typeColor>`，与现有 `.row.current` 叠加不冲突。
7. **磨砂玻璃作用域**：仅 `.filebar` 一层（`backdrop-filter: blur(8px)` + `background: rgba(255,255,255,.72)`）；`.diff-area` / `.dvt-tabbar` 不动，保证内容区文字不糊。
8. **不动部分**：tab 栏、PathBar（面包屑）、差异统计 `+−~=`、差异行定位、字符级光标、热力地图、Token 语法高亮、虚拟滚动、Git unified 模式、跨分栏横向滚动同步——**全部保留原样**。

**待确认边界（实施前用 AskUserQuestion 收口）：**

- 「git 短哈希」是否在 **无 mtime 信息** 时（如 jar 内 zip 资源）也展示？默认：是，回退 `path+size` 哈希。
- 「git 短哈希」在 **纯新增 / 纯删除** 一侧缺失时是否仍展示单边？默认：仅显示存在侧；缺失侧 `0000000`。
- 「filebar 磨砂玻璃」在 **暗色主题** 下是否需要单独适配？默认：是，按 `[data-bs-theme="dark"]` 切换 `background: rgba(28,30,34,.72)`。

---

### Task 1：后端 `DecompiledUnit` 增加 `oldHash` / `newHash` 短哈希字段

**Files:**
- Modify: `com/bempdiff/model/DecompiledUnit.java`
- Create: `com/bempdiff/util/ShortHash.java`（静态工具，复用 `MessageDigest`）
- Test: `com/bempdiff/test/TestRunner.java` 中新增 `testShortHash` 单元

- [ ] **Step 1：写失败测试**

```java
public static void testShortHash(TestContext t) {
    // 同一字节 → 同一短哈希
    byte[] a = "hello".getBytes(StandardCharsets.UTF_8);
    String h1 = ShortHash.ofBytes(a);
    String h2 = ShortHash.ofBytes(a);
    t.assertEq(h1, h2, "相同字节应得到相同哈希");
    t.assertEq(7, h1.length(), "短哈希固定 7 位");
    // 不同字节 → 不同哈希
    byte[] b = "world".getBytes(StandardCharsets.UTF_8);
    t.assertNe(h1, ShortHash.ofBytes(b), "不同字节应得到不同哈希");
    // 文本类：path+size+mtime 拼串
    String h3 = ShortHash.ofMeta("a/b/c.properties", 1024, 1700000000000L);
    String h4 = ShortHash.ofMeta("a/b/c.properties", 1024, 1700000000000L);
    t.assertEq(h3, h4, "相同 meta 应得到相同哈希");
    // 空输入 → 0000000
    t.assertEq("0000000", ShortHash.ofBytes(new byte[0]), "空字节回退 0000000");
}
```

- [ ] **Step 2：实现 `ShortHash`**

```java
package com.bempdiff.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 7 位短哈希（类比 git rev-parse --short=7）。空输入回退 "0000000"。 */
public final class ShortHash {
    private static final String EMPTY = "0000000";
    private ShortHash() {}
    public static String ofBytes(byte[] data) {
        if (data == null || data.length == 0) return EMPTY;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(7);
            for (int i = 0; i < 4; i++) { // 4 字节 → 8 hex → 取前 7
                String h = String.format("%02x", d[i] & 0xff);
                sb.append(h);
            }
            return sb.substring(0, 7);
        } catch (NoSuchAlgorithmException e) {
            return EMPTY;
        }
    }
    public static String ofMeta(String path, long size, long mtime) {
        if (path == null) return EMPTY;
        String key = path + "|" + size + "|" + mtime;
        return ofBytes(key.getBytes(StandardCharsets.UTF_8));
    }
    public static String ofPathAndSize(String path, long size) {
        if (path == null) return EMPTY;
        return ofMeta(path, size, 0L);
    }
}
```

- [ ] **Step 3：`DecompiledUnit` 加 9 参构造器 + 2 个字段**

```java
public final class DecompiledUnit {
    private final String oldSource;
    private final String newSource;
    private final String diffText;
    private final String engine;
    private final String error;
    private final boolean ok;
    private final String oldHash;  // 新增：老侧短哈希
    private final String newHash;  // 新增：新侧短哈希

    public DecompiledUnit(String key, String oldSource, String newSource, String diffText,
                          String engine, String error, boolean ok,
                          String oldHash, String newHash) {
        ...
        this.oldHash = oldHash != null ? oldHash : "0000000";
        this.newHash = newHash != null ? newHash : "0000000";
    }
    /** 旧 7 参构造器（兼容老调用点）转 9 参。 */
    public DecompiledUnit(String key, String oldSource, String newSource, String diffText,
                          String engine, String error, boolean ok) {
        this(key, oldSource, newSource, diffText, engine, error, ok, "0000000", "0000000");
    }
    public String getOldHash() { return oldHash; }
    public String getNewHash() { return newHash; }
    // ...其他 getter 保留
}
```

- [ ] **Step 4：更新 `Decompiler` 调点产出哈希**

在 [Decompiler.java](../../bempdiff/java_core/src/com/bempdiff/decompile/Decompiler.java) `decompileBytes()` 中（行 350 附近）：

```java
public DecompiledUnit decompileBytes(byte[] oldBytes, byte[] newBytes, String key, DiffRules rules) {
    try {
        String oldSrc = (oldBytes != null) ? decompileOne(oldBytes) : null;
        String newSrc = (newBytes != null) ? decompileOne(newBytes) : null;
        String diffText = (oldSrc != null && newSrc != null) ? computeDiff(oldSrc, newSrc) : null;
        // 短哈希：原字节侧计算；任一缺失侧填 0000000
        String oldHash = (oldBytes != null) ? ShortHash.ofBytes(oldBytes) : "0000000";
        String newHash = (newBytes != null) ? ShortHash.ofBytes(newBytes) : "0000000";
        return new DecompiledUnit(key, oldSrc, newSrc, diffText, "CFR", null, true, oldHash, newHash);
    } catch (Exception e) {
        ...
        // 失败时也尝试计算哈希：失败也保留「字节指纹」便于定位
        String oldHash = (oldBytes != null) ? ShortHash.ofBytes(oldBytes) : "0000000";
        String newHash = (newBytes != null) ? ShortHash.ofBytes(newBytes) : "0000000";
        return new DecompiledUnit(key, null, null, null, "CFR", e.getMessage(), false, oldHash, newHash);
    }
}
```

文本类（非 class）走另一条路径（`DiffEngine` / `FrontendTextDiff`），在 `BempServer.getAiWork` / `BempServer.handleEntryDecompile` 内统一补哈希（详见 Task 2）。

- [ ] **Step 5：测试通过**

运行 `java -cp classes com.bempdiff.test.TestRunner testShortHash testDecompileUnitHashes`，断言全部通过。

- [ ] **Step 6：编译并入 `dist_input/classes` + 重打包 jar**

`javac -d dist_input/classes -cp cfr.jar;toolchainib`，再 `jar cfe bempdiff.jar com.bempdiff.Main -C classes .`（按 `project_memory.md` 教训，必须用最新 `dist_input/classes`，且杀后端 + electron 重启）。

---

### Task 2：`BempServer.handleEntryDecompile` 响应透出短哈希

**Files:**
- Modify: `com/bempdiff/server/BempServer.java`（`handleEntryDecompile` 方法内构造响应 Map 处）
- Modify: `com/bempdiff/server/Json.java`（确认字段顺序包含 `oldHash`/`newHash`）

- [ ] **Step 1：找到响应构造点**

在 `handleEntryDecompile` 内：当前构造 `Map<String, Object>` 后 `sendJson` 返回 `{oldSrc, newSrc, diffText, engine, ok}`。追加 `oldHash` / `newHash` 字段。

```java
private void handleEntryDecompile(HttpExchange ex, Job job) throws IOException {
    ...
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("oldSrc", u.getOldSource());
    resp.put("newSrc", u.getNewSource());
    resp.put("diffText", u.getDiffText());
    resp.put("engine", u.getEngine());
    resp.put("ok", u.isOk());
    resp.put("oldHash", u.getOldHash());  // 新增
    resp.put("newHash", u.getNewHash());  // 新增
    if (u.getError() != null) resp.put("error", u.getError());
    sendJson(ex, 200, resp);
}
```

- [ ] **Step 2：文本类响应也补哈希**

文本类（`text: {key, oldText, newText, diffText, engine}`）走另一条路径：在构造 text 响应 Map 处用 `ShortHash.ofMeta(key, oldSize, oldMtime)` 算 oldHash（老侧）；文本侧没有 mtime 时回退 `ShortHash.ofPathAndSize(key, newSize)`。新增 helper `BempServer.buildTextResponse(...)` 统一封装。

- [ ] **Step 3：单元测试**

`BempServerTest.testDecompileResponseIncludesHash`：发起一次真实 class 比对，断言响应 JSON 含 `oldHash` / `newHash` 字段且 7 位 hex；纯新增类断言 `oldHash == "0000000"`。

- [ ] **Step 4：手动 curl 验证**

```bash
curl -s 'http://127.0.0.1:18765/api/entry/decompile?jobId=<id>&key=<key>' | python -c "import sys,json;d=json.load(sys.stdin);print('oldHash=',d.get('oldHash'),'newHash=',d.get('newHash'))"
```

预期输出 7 位 hex。

---

### Task 3：前端 `DiffView.vue` filebar 加 git 短哈希标题 + rep 行虚线分隔

**Files:**
- Modify: `bempdiff/webui/src/components/DiffView.vue`（template + script + style）
- Modify: `bempdiff/webui/src/store.js`（`getDecompile` 解析 `oldHash`/`newHash`）

- [ ] **Step 1：`store.js` 解析新字段**

在 `getDecompile` 调 `api.decompile(...)` 的赋值处，扩展对象字面量：

```js
const r = await api.decompile(state.job.jobId, key, { signal })
state.tabs[idx].decompile = {
  oldSource: r.oldSrc || '',
  newSource: r.newSrc || '',
  diffText: r.diffText || '',
  engine: r.engine || 'CFR',
  ok: !!r.ok,
  oldHash: r.oldHash || '0000000',  // 新增
  newHash: r.newHash || '0000000'   // 新增
}
```

文本类（`api.decompileText` 或类似）若走 `state.tabs[idx].text` 分支也补同两字段。

- [ ] **Step 2：filebar 模板新增 git 短哈希徽标**

`DiffView.vue` template 内 `filebar` 区域（约第 742 行后），在 `<PathBar>` 与 filebar 末尾按钮组之间，插入：

```html
<span class="git-hash-tag ms-2" v-if="at && at.decompile"
      :title="'提交：' + at.decompile.oldHash + ' → ' + at.decompile.newHash">
  <i class="bi bi-hash"></i>
  <code class="gh-old">{{ at.decompile.oldHash }}</code>
  <i class="bi bi-arrow-left-right gh-arrow"></i>
  <code class="gh-new">{{ at.decompile.newHash }}</code>
</span>
```

- [ ] **Step 3：rep 行中央虚线分隔（仅不换行 split 模式）**

在 `split` 模板的 `<span class="prow rep">` 内（行 871-874 附近），给 rep 行的 `code` 单元格右侧加 `class="rep-divider"`，并在 `style` 段加：

```css
.prow.rep .code { border-right: 1px dashed var(--bs-warning); }
.prow.rep .code { padding-right: .8rem; }
.prow.rep + .prow.rep .code { border-top: 1px solid var(--bs-warning-bg-subtle); }
```

注意：当前模板同一 rep 行在左右两栏各渲染一次（左/右 code 各自独立），所以"中央虚线"实际是 rep 行右 code 单元格右侧的一道 1px 虚线（左右两栏中间的 `.diff-splitter` 本身就是 4px 实色块），虚线放在右 code 右缘用以"标记 rep 行内"分隔。

- [ ] **Step 4：filebar 短哈希 CSS**

```css
.git-hash-tag {
  display: inline-flex; align-items: center; gap: .35rem;
  font-size: .72rem; font-family: var(--bs-font-monospace);
  padding: .1rem .5rem; border-radius: .375rem;
  background: var(--bs-tertiary-bg);
  border: 1px solid var(--bs-border-color);
  color: var(--bs-secondary-color);
}
.git-hash-tag code { font-size: .72rem; padding: 0; }
.git-hash-tag .gh-old { color: var(--bs-secondary-color); }
.git-hash-tag .gh-new { color: var(--bs-primary); font-weight: 600; }
.git-hash-tag .gh-arrow { font-size: .68rem; opacity: .55; }
```

---

### Task 4：CSS 美化主包（差异色条 / 整行加色 / 行内色块反色 / 磨砂玻璃 / 当前行描边）

**Files:**
- Modify: `bempdiff/webui/src/components/DiffView.vue`（`<style scoped>` 段）

- [ ] **Step 1：差异色条 = gutter 列升级**

把现有 `.row .gt` / `.prow .gt` 由「图标居中无左色条」改为「左侧 3px 主色条 + 图标居中」：

```css
.row .gt, .prow .gt {
  position: relative;
  display: flex; align-items: center; justify-content: center;
  font-size: .68rem; user-select: none;
  border-right: 1px solid var(--bs-border-color);
}
.row .gt::before, .prow .gt::before {
  content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px;
  background: transparent;
}
.row .gt.gt-add::before, .prow .gt.gt-add::before { background: var(--bs-success); }
.row .gt.gt-del::before, .prow .gt.gt-del::before { background: var(--bs-danger); }
.row .gt.gt-rep::before, .prow .gt.gt-rep::before { background: var(--bs-warning); }
```

- [ ] **Step 2：整行加色（删除行 / 新增行）**

```css
.row.del .code, .prow.del .code { color: var(--bs-danger); }
.row.add .code, .prow.add .code { color: var(--bs-success); }
.row.del .code .im-del, .prow.del .code .im-del { color: var(--bs-danger); font-weight: 600; }
.row.add .code .im-add, .prow.add .code .im-add { color: var(--bs-success); font-weight: 600; }
```

- [ ] **Step 3：行内差异片段反色块（替代半透明叠加）**

```css
.code .im-del { background: #fff5f5; color: #c92a2a; border-radius: 2px; box-shadow: inset 0 0 0 1px rgba(220,53,69,.18); }
.code .im-add { background: #ebfbee; color: #2b8a3e; border-radius: 2px; box-shadow: inset 0 0 0 1px rgba(25,135,84,.18); }
/* rep 行（淡橙底）内差异片段加深 */
.row.rep .code .im-del, .prow.rep .code .im-del { background: #ffe3e3; color: #c92a2a; }
.row.rep .code .im-add, .prow.rep .code .im-add { background: #d3f9d8; color: #2b8a3e; }
```

- [ ] **Step 4：filebar 磨砂玻璃**

```css
.filebar {
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  background: rgba(255,255,255,.72);
  border-bottom: 1px solid var(--bs-border-color);
}
[data-bs-theme="dark"] .filebar { background: rgba(28,30,34,.72); }
/* 工具按钮：磨砂底 + 阴影 + 圆角浮起 */
.filebar .btn { border-radius: .5rem; box-shadow: 0 1px 2px rgba(0,0,0,.04); }
.filebar .btn:hover { box-shadow: 0 2px 6px rgba(0,0,0,.08); transform: translateY(-1px); transition: all .15s ease; }
```

- [ ] **Step 5：当前行双层主色描边**

```css
.row.current, .prow.current { box-shadow: inset 6px 0 0 var(--dt-accent, var(--bs-primary)); }
.row.current .ln, .prow.current .ln { color: var(--dt-accent, var(--bs-primary)); font-weight: 700; }
.row.current .code, .prow.current .code { background: color-mix(in srgb, var(--dt-accent, var(--bs-primary)) 6%, transparent); }
```

- [ ] **Step 6：折叠占位条加类型色圆点**

```css
.row.fold .fold-ph::before, .prow.fold .fold-ph::before {
  content: ''; display: inline-block; width: .5rem; height: .5rem;
  border-radius: 50%; margin-right: .4rem; vertical-align: middle;
  background: var(--dt-accent, var(--bs-secondary-color)); opacity: .6;
}
```

---

### Task 5：端到端验证

**Files:**
- Modify: `bempdiff/webui/src/components/DiffView.vue`（无）
- Test: `com/bempdiff/test/TestRunner.java` 中 `testDecompileResponseIncludesHash`

- [ ] **Step 1：后端回归**

`java -cp classes com.bempdiff.test.TestRunner`：所有 195 个测试全部通过（含新增 2 个）。

- [ ] **Step 2：桌面端真实验证**

1. 杀旧 javaw + electron；`BEMPDIFF_FRONTEND=build tooling/scripts/启动服务.bat` 启动（按 `project_memory.md` 教训，必须保证拉起的是新编译的 `dist_input/classes`）。
2. 打开任意一对含 Java 类的 war/zip 比对。
3. 选中一个修改类，对比栏应展示：
   - filebar 出现 `(...hash ↔ ...hash)` 短哈希徽标（旧哈希灰、新哈希蓝、箭头灰）。
   - rep 行右 code 单元格右缘 1px 虚线（仅 split 模式）。
   - gutter 列左 3px 色条（红/绿/橙）+ 图标居中。
   - 删除行整行红、新增行整行绿、修改行整行淡橙。
   - 行内差异片段用白底红字 / 白底绿字反色块（替换原半透明叠加）。
   - filebar 磨砂玻璃 + 按钮 hover 上浮。
   - 当前行 6px 主色描边 + 极淡主色底。
4. 切到 wrap 模式：虚线分隔自动消失（不影响单栏）。
5. 切到 Git unified 模式：色条 / 整行加色 / 虚线 / 磨砂均保留，git 短哈希徽标保持。
6. 纯新增类：左哈希 `0000000`、右哈希正常 7 位。
7. 纯删除类：右哈希 `0000000`、左哈希正常 7 位。

- [ ] **Step 3：截图对比**

前后截图（如 `docs/screenshots/diffview-美化前.png` / `美化后.png`），重点对比：
- 整行加色前后
- 行内色块反色前后
- 短哈希徽标存在性
- 磨砂玻璃效果

---

## 阶段交接声明

- 当前阶段：计划阶段 ✅ 已完成
- 下一阶段：实施阶段（Task 1 → Task 5 串行）
- 下一阶段智能体：当前会话（保持上下文）
- 下一阶段技能：vue 前端（DiffView.vue） + java 后端（DecompiledUnit/ShortHash/BempServer）按 Task 顺序
- 交接上下文：见本计划书；本任务范围 = 全套 IDE 化（含 git 短哈希），模式 = 多阶段，先确认再编码

---

## 风险与回退

1. **后端序列化兼容性**：`DecompiledUnit` 新字段追加在末尾，前端 `getDecompile` 解析做 `r.oldHash || '0000000'` 兜底，**生产 jar 即使未升级，前端拿到 undefined 也仅显示 `0000000`，不报错**。
2. **CSS 主题适配**：暗色主题 `[data-bs-theme="dark"]` 已加分支适配磨砂玻璃；行内反色块在暗色下用同一组 hex（白底 → 暗色下可读性略降，但已通过 `box-shadow inset 1px` 描边强化）。如暗色下体验不佳，可后续 PR 调整。
3. **性能影响**：短哈希计算发生在反编译入口前，对原始字节 SHA-1 一次（1KB 内 < 0.1ms），不构成瓶颈。
4. **回退**：所有改动都是**纯追加**：后端只加 2 个字段 + 1 个新工具类；前端只加 1 个 span + 1 段 CSS。回退只需 git revert 即可。
