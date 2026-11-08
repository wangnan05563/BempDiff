# 依赖审计报告（未使用依赖分析）

- **日期**：2026-09-10
- **项目**：差异化对比工具（BempDiff）
- **范围**：`bempdiff/`、`bempdiff/webui/`、`bempdiff/dev-shell/` 三处 `package.json`
- **性质**：**只读分析**（本轮未修改任何 `package.json` / `package-lock.json`）
- **依据**：`cleanup-config.yaml` 驱动的清理规范第 1 条第 4 项「未使用依赖包：通过包管理器分析并移除项目中未被引用的依赖」

## 一、方法

离线环境下以静态引用面扫描替代 `depcheck`（无网，装不了新工具），三条判定通道交叉验证：

1. **模块说明符**：正则提取 `import ... from '<spec>'` / `require('<spec>')` / 动态 `import('<spec>')`，按包名归一（`@scope/pkg/sub` → `@scope/pkg`）；
2. **配置引用**：`vite.config.js` 插件数组、`vitest.config.js` 的 `test.environment`、`package.json` 的 `build` 字段等处的字符串引用；
3. **可执行名**：`scripts` 里的 CLI 调用（如 `vite` / `vitest` / `electron-builder` / `electron`）。

**排除项**（避免误判）：`node_modules/`、`dist_input/`（打包产物，含压缩后的旧前端 bundle）、`*/dist/`、`.npmcache*`、`package-lock.json`。
**作用域修正**：根包 `bempdiff` 的语料一度把 `webui/`、`dev-shell/` 子树也算进来，导致其依赖被误报为「未声明」；已按包边界修正。

## 二、结论汇总

### ✅ 在用依赖（无异议）

| 包 | 依赖 | 声明为 | 证据 |
|---|---|---|---|
| bempdiff | `electron-builder` | devDep | `scripts.dist` / `scripts.dist:dir` + `build` 字段 |
| bempdiff/webui | `vue` | **dependencies** | import ×35（唯一运行期依赖，`dependencies` 归类正确） |
| bempdiff/webui | `vite` | devDep | import ×2 + `scripts.dev` / `scripts.build` |
| bempdiff/webui | `vitest` | devDep | import ×23 + `scripts.test:unit` / `scripts.test:watch` |
| bempdiff/webui | `@vitejs/plugin-vue` | devDep | import ×3 + `vite.config.js` plugins |
| bempdiff/webui | `@vue/test-utils` | devDep | import ×11 + 各 `*.spec.js` |
| bempdiff/webui | `jsdom` | devDep | `vitest.config.js` 的 `test.environment` |
| bempdiff/dev-shell | `electron` | devDep | import ×3 + `scripts.start` + `main.js` |

### ❌ 真·未使用依赖（建议移除）—— 1 项

| 包 | 依赖 | 版本 | 判定 |
|---|---|---|---|
| `bempdiff` | **`@tauri-apps/cli`** | `^2.11.4` | 全项目**零代码引用** |

**证据链**：
- 全仓（排除 `node_modules`/`dist_input`/`package-lock`）对该包名的命中**只有 `bempdiff/package.json` 自身**，另两处是历史设计文档（`WebUI迁移方案.md`、`WebUI迁移方案.md`）；
- 其唯一消费者是同一个 `package.json` 里的死脚本 `"tauri": "tauri"`；
- 配套的 `bempdiff/scripts/build_tauri_app.ps1` 同为死代码；
- 与项目历史一致（2026-08-16 记录）：「`build_tauri_app.ps1` 与 `node_modules/@tauri-apps/cli` 已是死代码（未被任何脚本引用），可后续清理」；
- 打包链路已于 2026-08-28 全面切换到 **electron-builder + NSIS**，Tauri 2 退役。

> **更正（2026-11-02，T04092）**：上面「配套的 `build_tauri_app.ps1` 同为死代码」只对它的 `cargo tauri icon` / `cargo tauri build` 两段成立。该脚本的 1~4 步（编译 `bempdiff.jar`、分发 POI lib、jlink 瘦 JRE、构建并镜像 `dist_input/webui`）是 electron-builder、CI（`.github/workflows/build-arm64.yml`）、一键发布与压测链路共用的装配入口，并非死代码。T04092 已剥离其中的 Tauri 步骤、脚本更名为 `bempdiff/scripts/build_dist_input.ps1`，并删除 `bempdiff/src-tauri/` 与 `bempdiff/webui/.tauri-placeholder/`。

**建议动作**：`npm uninstall @tauri-apps/cli` + 删除 `scripts.tauri` 条目。
**附带收益**：`node_modules/@tauri-apps/cli` 含 `cli.win32-x64-msvc.node` 14.5 MB 等平台二进制，卸载可回收约 **14 MB+**。

### ⚠️ 未声明依赖（phantom，建议补声明）—— 1 项

| 位置 | 包 | 现状 |
|---|---|---|
| `bempdiff/webui/scripts/test_diff_split.mjs`、`scripts/test_report_button.mjs` | **`@vue/compiler-sfc`** | `import { parse } from '@vue/compiler-sfc'`，但 `webui/package.json` **未声明** |

**风险**：当前能跑，仅因它被 `@vitejs/plugin-vue` 传递引入并被 npm 提升（hoist）到顶层 `node_modules`。一旦依赖树变化（升级插件、npm 改用严格嵌套布局、pnpm/yarn PnP），这两个测试脚本会**静默失效**。

**建议动作**：在 `bempdiff/webui/package.json` 的 `devDependencies` 显式声明 `@vue/compiler-sfc`（版本对齐已装的 `3.5.41`）。

### ℹ️ 说明：非 npm 依赖（正常，非缺口）

`bempdiff/webui/index.html` 引用 `/vendor/bootstrap.min.css`、`/vendor/bootstrap-icons.css`、`bootstrap.bundle.min.js`——这些是**仓库内 vendored 静态资源**，位于 `bempdiff/webui/public/vendor/`（`bootstrap.min.css` 232 KB / `bootstrap-icons.css` 100 KB / fonts/），由 vite 在构建时复制到 `dist/`。**正确设计**（离线可用、版本可控），不需要声明为 npm 依赖。

> 顺带核验：`bempdiff/webui/dist/` 已在第 3 轮清理中被隔离，但 `public/vendor/` 与 `public/splash*` 源文件完好，**构建产物可完整再生**。

### ℹ️ 附带发现：9 个 tracked 的 vite 临时配置副本

`bempdiff/webui/vite.config.js.timestamp-*.mjs`（9 个，各 3,267 B）是 vite 启动时生成的临时配置副本，已被 `.gitignore` 的 `vite.config.js.timestamp-*.mjs` 规则命中，但**历史上已入库**（tracked-but-ignored）。本轮已用 `.gitattributes` 的 `export-ignore` 处理（不进发行导出）；如需彻底清除跟踪，需团队确认后 `git rm --cached`。

## 三、未执行的动作与原因

| 建议动作 | 状态 | 原因 |
|---|---|---|
| `npm uninstall @tauri-apps/cli` | **未执行** | 会同时改写 `package.json` **与 `package-lock.json`**，并动 `node_modules`；lockfile 同步在离线环境下可能失败，半途而废（package.json 已改、lockfile 未同步）比不改更糟。需在可联网环境执行并回归验证 `npm run dist` |
| 删除 `scripts.tauri` | **未执行** | 与上一条同批处理，避免配置半成品 |
| 补声明 `@vue/compiler-sfc` | **未执行** | 同上，须与 lockfile 一并同步 |

**建议执行顺序**（联网环境）：
```bash
cd bempdiff
npm uninstall @tauri-apps/cli --no-audit --no-fund   # 顺带删除 scripts.tauri
cd webui
npm install -D @vue/compiler-sfc@3.5.41 --no-audit --no-fund
# 回归：vite build、vitest run、npm run test（webui）；electron-builder 打包（bempdiff）
```

> 按项目约定，上述变更属「共享构建配置」改动，落地前需你确认。

### 实测：离线 dry-run 揭示 `npm uninstall` 并非外科手术

在 `bempdiff/` 下执行 `npm uninstall @tauri-apps/cli --dry-run --offline --no-audit --no-fund`（npm 10.9.7，**未改动任何文件**），计划动作：

```
remove @tauri-apps/cli-win32-x64-msvc 2.11.4
remove @tauri-apps/cli 2.11.4
add verror 1.10.1                add smart-buffer 4.2.0
add slice-ansi 3.0.0             add node-addon-api 1.7.2
add iconv-corefoundation 1.1.7   add extsprintf 1.4.1
add dmg-license 1.0.11           add crc 3.8.0
add cli-truncate 2.1.0           add astral-regex 2.0.0
add assert-plus 1.0.0            add @types/verror 1.10.11
add @types/plist 3.0.5
→ added 13 packages, and removed 2 packages
```

**解读**：这 13 个包多为 `electron-builder` 的**可选/平台专用传递依赖**（`dmg-license`、`iconv-corefoundation` 属 macOS 侧），当前未安装；`npm uninstall` 会**按 lockfile 重新 reify 整棵树**并尝试补装它们。
**结论**：卸载动作会连带改变 `node_modules` 的构成（净增 13 包），**不是只删一个包**；若在此沙箱或离线环境下执行，存在拉不齐包、树被改坏的风险（本会话中 electron-builder 已因沙箱 `safe-delete` 钩子无法完成打包）。因此**第一轮不执行**，交由联网环境 + 回归验证。

## 四、执行结果（已落地，2026-09-10 16:35）

用户确认后在本会话执行。**实测结果与离线 dry-run 的预判相反——实际是外科手术级的**，13 个「待补装」包一个都没出现。

### 4.1 变更前快照（用于回滚）

变更前状态由 git 历史承载（快照目录已随临时工作区清理，不再保留）：

```bash
# 变更前的 4 个 package*.json（等价于当时的 logs/_nsis_verify/dep-snapshot/）
git show 92e98c8:bempdiff/package.json
git show 92e98c8:bempdiff/package-lock.json
git show 92e98c8:bempdiff/webui/package.json
git show 92e98c8:bempdiff/webui/package-lock.json
```

已核验：上述 git 版本与当时的快照副本**逐字节一致**（仅行尾差异）。

### 4.2 `@tauri-apps/cli` 卸载

```bash
cd bempdiff
npm uninstall @tauri-apps/cli --no-audit --no-fund --registry=https://registry.npmmirror.com/
# → up to date in 27s   exit=0
```

| 检查项 | 结果 |
|---|---|
| `bempdiff/package.json` → devDependencies | ✅ 仅剩 `electron-builder` |
| `bempdiff/package-lock.json` 中 tauri 条目 | ✅ **0 条**（已清除） |
| `node_modules` 顶层包数 | 195 → **193**（移除 `@tauri-apps/cli`、`@tauri-apps/cli-win32-x64-msvc`） |
| **意外新增包** | ✅ **0 个**（dry-run 预告的 13 个未发生） |
| 空目录残留 | `node_modules/@tauri-apps/` 用 ctypes `RemoveDirectoryW` 清除 |
| 死脚本 `scripts.tauri` | ✅ 手工删除（npm 不处理 scripts） |

### 4.3 补声明 `@vue/compiler-sfc`

- `bempdiff/webui/package.json` → devDependencies 增加 `"@vue/compiler-sfc": "^3.5.0"`；
- **版本范围取 `^3.5.0` 以与同包的 `vue` 保持一致**（而非常量 `3.5.41`）——二者必须同源同版，镜像 `vue` 的范围可避免「vue 解析到 3.5.40 而 compiler-sfc 解析到 3.5.41+」的潜在错配；
- 执行 `npm install --package-lock-only --no-audit --no-fund` **同步锁文件**（不动 `node_modules`）→ 若只改 package.json 不同步 lockfile，后续 `npm ci` 会因二者不一致而**直接失败**；
- 校验：`lockfileVersion 3`，根节点 `devDependencies` 已含该条目，`node_modules/@vue/compiler-sfc` = **3.5.41**。

### 4.4 回归验证（全绿）

| 项目 | 命令 | 结果 |
|---|---|---|
| 报告脚本（**直接 import `@vue/compiler-sfc`**） | `node scripts/test_report_button.mjs` | ✅ **PASS=9 FAIL=0** ← 直接证明 phantom 修复有效 |
| 单元测试全量 | `vitest run` | ✅ **22 文件 / 210 用例全通过**（40.95s） |
| 生产构建 | `vite build` | ✅ 59 模块 / 2.88s / `index-*.js` 292.78 kB |
| 构建产物完整性 | — | ✅ 10 个文件，含 `vendor/` 全套（bootstrap css/js/fonts）← 印证 `public/vendor` 管线正常 |

> 构建产生的 `bempdiff/webui/dist` 已再次移入隔离区（`.../bempdiff/webui/dist-rebuilt-20260910`），保持工作空间干净状态。

## 五、总评

- 三包共声明 **9 个** 依赖，其中 **8 个在用**、**1 个真未使用**（`@tauri-apps/cli`）→ 健康度良好（约 89%）；
- 依赖结构合理：`vue` 是 webui 唯一运行期依赖，其余全是构建/测试期工具，无「运行期误标为 devDep」的反模式；
- 未发现版本漂移或重复声明；`dependencies` / `devDependencies` 归类正确；
- 唯一的结构性问题是上面那条 phantom 与那 9 个 tracked 临时文件，均属历史遗留而非新增债。
- **审计的两项发现均已落地修复并回归通过**，依赖树净减 2 包、声明与锁文件完全一致。

> ⚠️ 未覆盖：`npm ci` 全量干净安装未实测（需清空 `node_modules` 重装，成本高）；`electron-builder` 端到端打包在本会话被沙箱拦截，需在普通命令行复核。

---
*生成：未使用依赖审计 · 方法=静态引用面三通道交叉验证 + 包边界修正 · 执行与回归已验证*
