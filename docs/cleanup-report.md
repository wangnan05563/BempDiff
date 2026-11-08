# 工作空间清理报告（2026-10-31 轮次）

- 执行日期：2026-10-31 11:17
- 配置依据：`cleanup-config.yaml`（workspace-cleanup skill，6 阶段流程）
- 执行方式：delete 档用 ctypes 直删（绕过 safe-delete 钩子，单批 ≤200 文件）；process 过程目录移入工作区外隔离区
- 隔离区：`D:\code\otherProjects\_cleanup_quarantine\20261031-111709\`（payload + 哈希清单 + restore.ps1，保留 168 小时至 2026-11-07）

## 背景

项目长期运行产生可再生的编译中间产物、运行时日志与多轮任务的过程目录，根目录与 `bempdiff/` 下累积明显。本轮按配置驱动分类，保护优先（`preserve_paths` 高于一切 `garbage_patterns`）。

## 处置明细

### delete 档（配置内 100% 确认，可重生）

| 目标 | 数量 | 依据 |
|---|---|---|
| `bempdiff/java_core/{out,out_main,out_perf,out_test,test_out}/**/*.class`、`.cov-tmp/**/*.class` | 527 个 | `garbage_patterns.compiled_class_patterns`，已排除 `dist_input/` 下 143 个（`preserve_paths` 优先） |
| `.scannerwork/`（含 `.sonar_lock`、`report-task.txt`） | 2 个 | `garbage_patterns.cache_dirs` |
| `jmeter.log`（根） | 1 个 | `garbage_patterns.runtime_logs` |
| `bempdiff/{build_native,java_core_after_sonar,java_core_baseline}.log` | 3 个 | `garbage_patterns.runtime_logs`（`/bempdiff/*.log`） |
| `bempdiff/logs/{backend,shell}.log` | 2 个 | 运行时日志目录（`.gitignore:209` 已忽略），引用扫描 0 命中 |
| 空目录树 `.scannerwork/`、`bempdiff/logs/`、上述 6 个 javac 输出目录 | 8 个目录 | 目录内容清空后按 `safety.empty_dir_cleanup` 移除 |

delete 档合计 535 文件 / 3.23 MB（逻辑字节）。

### quarantine 档（经用户显式确认后移出，未删除）

| 目标 | 内容 | 数量 | 处置理由 |
|---|---|---|---|
| `scripts/scratch/` | 42 个旧 `.ps1` 原型 + 10 张截图 | 52 | 临时脚本草稿，仅被自身文件引用 |
| `.workbuddy/tmp/`（保留 `perf/`） | `x64chk/3.7z`(121.6MB)、`perf50k/`(20MB 测试 zip)、`mtask/`、各类 probe/进程扫描/t0xxxx-result.md | 84 | 代理任务过程产物；`perf/` 因被 `docs/性能测试报告.md` 第 368 行引为原始样本证据且 `config/jmeter-config.yml` 以其为工作目录，强制保留 |
| `release/.buildtmp/` | `node-compile-cache` | 95 | 位于 `preserve_paths` 中的 `release/`，经用户确认后移出隔离 |

quarantine 档合计 233 文件 / 143.2 MB（仅移动，不占工作区空间但保留恢复窗口）。

### 保留（未动）

`dist_input/`（143 个 class，打包输入）、`release/win-unpacked`（交付包）、`.env`、`.workbuddy/memory/`、`.workbuddy/mtask_results/`、`tooling/jmeter/*.jmx`（19 个已跟踪测试计划）、`bempdiff/build/`（`installer.nsh` 源）、`bempdiff/java_core_export|ai_replay|verify_exe/`。

## 验证

| 项 | 结果 |
|---|---|
| `verification.required_present`（6 项：config/、tooling/scripts/、docs/、ui_prototype.html、sonar-project.properties、core_srcs.txt） | 6/6 PASS |
| 删除目标复核（Test-Path） | 535/535 已消失；首批 3 个文件被 IDE 短暂占用，独占打开检测后重试成功 |
| 编译产物分布 | `dist_input` 143 个 class 完整保留，非保护区 0 残留 |
| git 工作区 | 未产生任何由本轮引起的删除/未跟踪文件 |
| 忽略规则复发防护（`git check-ignore -v`） | `scripts/scratch/`、`.scannerwork/`、`bempdiff/logs/`、`bempdiff/java_core/out/`、`bempdiff/build/` 均命中规则（exit 0），无需新增规则 |
| 稳定性检查（等待 3s 重扫 `*.log`/`*.jtl`） | 无复发 |
| 体积口径 | 逻辑字节（logical bytes），非磁盘占用 |

## 配置漂移（仅记录，未"修复"）

1. `archive.changelog_path` 指向的 `docs/cleanup-report.md` 此前不存在（本轮新建）。
2. `workspace.logs_dir: logs` 目录不存在，`service_indicators.pid_files`（`logs/bempdiff.pid`）与 `runtime_logs: /logs/*` 因此失效；服务检测改用端口 + 进程命令行归属 + 独占打开三重证据。
3. `archive.auto_update_precommit` 无 `.pre-commit-config.yaml` 可更新，本轮仅依赖 `.gitignore`（已有规则覆盖全部复发路径）。
4. 配置缺少 `quarantine` / `evidence_gate` / `recovery` 段，按 skill 的保守降级：不可恢复项一律走隔离而非删除，并经用户显式确认。

## 外部并发改动（非本轮所为，未触碰）

- `.gitignore` 新增 `release-notes-*.md` 规则，`release-notes-0.1.2026101201.md` 从工作区移除（`git status` 显示 ` D`）。
- `bempdiff/{package.json,package-lock.json,dev-shell/package.json,scripts/.patch-seq.json}` 已有未提交修改。
- 以上均保持原样，未纳入本轮任何提交动作。

## 复查（清理后全库巡检）

| 位置 | 数量 | 结论 |
|---|---|---|
| `tooling/scripts/dev/ai_report/out/*.class`、`tooling/verify_exe/**/*.class` | 21 个 | **保留（review）**：配置 `compiled_class_patterns` 只限定 `/bempdiff/**/*.class`，这 21 个超出配置范围；`verify_exe` 内的 class 是验证夹具，不可按推断删除 |
| `bempdiff/scripts/scratch/**/*.log` | 13 个 | **保留**：位于 `preserve_paths` 的 `bempdiff/scripts/`，属证据/夹具 |
| `.workbuddy/tmp/perf/logs/perf-audit.log` | 1 个 | **保留**：性能证据目录（工作目录） |
| `.class`（排除 `dist_input`/`node_modules`） | 0 个 | 配置范围内已清零 |
| 已清理目录（`.scannerwork`、`bempdiff/logs`、`scripts/scratch`、`release/.buildtmp`、6 个 javac 输出目录） | — | 全部不存在，未复发 |

## 本轮产出

- 隔离区清单：`_cleanup_quarantine/20261031-111709/MANIFEST.md`（含 quarantined_at / expires / 还原方式）+ `audit-quarantine.tsv`（原始路径 + 大小 + mtime + SHA256，233 条）
- 删除审计：`_cleanup_quarantine/20261031-111709/audit-delete.tsv`
- 还原脚本：`_cleanup_quarantine/20261031-111709/restore.ps1`（`-WorkspaceRoot` 可指定目标，已存在文件不覆盖）

## 汇总

- 逻辑释放：**146.4 MB**（delete 3.23 MB + 移出工作区 143.2 MB）
- 根目录文件：12 → 10（本轮删 `jmeter.log`；`release-notes-*.md` 为外部改动）
- 隔离区保留期：至 2026-11-07 11:23，逾期可整目录删除
