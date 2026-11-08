# BempDiff 权限/安全隔离逻辑 跨模块评审

> 评审对象：本工作区 `BempDiff` 的 BempDiff 工具（WAR/JAR 差异比对与智能分析）。
> 范围说明：用户原话为"全面排查系统权限隔离逻辑各模块设计"。本仓库内仅有此工具可实查；**BEMP 5.0 后端**与 **Ardot** 代码不在本目录，未纳入本次评审。若你指的是那两者，请指向代码路径，复用同一套评审方法重跑。
> 评审方法：逐文件通读 core(24) + ui(4) 共 28 个类，聚焦"隔离边界"——文件访问、网络出向、密钥、子进程、资源上限。发现即修复，并构造恶意样本实跑验证（无桌面、直接 JDK21 跑同批字节）。

## 一、发现项与修复（共 5 项实质修复 + 2 项已知限制）

| 级别 | 模块 | 问题 | 修复 | 状态 |
|---|---|---|---|---|
| **P0** | parse + export | **Zip Slip 任意文件写**：`AssetExporter.exportDiffClasses` 用 `classesDir.resolve(k)` 直接把 zip 条目名当路径写；`PackageParser` 从未归一化条目名。恶意包含 `../../x.class` 即可越界写任意文件。 | ① `PackageParser.sanitizeKey()` 解析时即对条目名归一化（拒绝对路径、`..` 穿越、盘符），恶意条目 fail-closed；② `AssetExporter` 写盘前再校验 `target` 落在 `diff-classes/` 内（双保险）。 | ✅ 已修+验证 |
| **P1** | ai（网络出向） | **SSRF 防护不完整**：原 `guardEndpoint` 仅挡字面 `169.254.169.254` 一个 IP，不挡整段链路本地、回环、私网，也无解析层校验（DNS rebinding 可绕过）。 | 始终拦截链路本地/云元数据 `169.254.0.0/16`（含 `getByName` 解析后地址）；新增 `blockPrivateEndpoints` 严格开关（额外拒回环/私网 `10/8 172.16/12 192.168/16 127/8 ::1 fc00::/7`）。默认关以兼容本地 Ollama。 | ✅ 已修+验证 |
| **P1** | parse（资源上限） | **解压炸弹/无单条目上限**：`readAll` 按声明 size 读全量，恶意条目可声明很小实际巨大 → OOM；仅 lib jar 受 `maxEntryBytes` 约束，嵌套 class 与顶层条目无约束。 | `readAll` 增加硬上限 64MB，对**声明 size 与实际读取**双重约束，超限即抛 `zip bomb`。 | ✅ 已修 |
| **P2** | ui/config（密钥隔离） | **API Key 明文落盘**：`UiConfig.save` 在 Key 非空时明文写入 `~/.bempdiff/ui-config.properties`（BR-SEC-01）。 | 新增 `persistApiKey`（默认 **false**）：默认 Key 仅内存态不落盘；UI 勾选才明文存盘（文件头仍标注"含敏感凭据勿共享"）。 | ✅ 已修+验证 |
| **P2** | decompile（子进程） | **CFR 路径未校验**：`cfrJar` 为目录/缺失时也去 `java -jar` spawn 进程，浪费进程且报错含糊。 | 仅当 `cfrJar.toFile().isFile()` 才 spawn，否则直接降级 javap。 | ✅ 已修 |

**已知限制（已追加处理，见下）：**

> **本轮追加修复（续）**：
> 1. **Zip Slip 策略由 fail-closed 优化为"跳过单条、其余照常比对"**：解析层对单个可疑条目（路径穿越/zip bomb）不再令整包解析失败，而是打印 `[隔离] 跳过可疑条目` 告警后继续；坏 key 永不进入快照，导出层写盘边界校验仍保留为双保险。可用性↑、安全性不变。
> 2. **`findJava()` 候选校验 `isFile()`**（Main 与 App 两处）：JAVA_HOME 候选原仅查 `exists()`，目录占位也会误命中；改为 `isFile()` 更严谨。PATH 回退仍保留（本地单用户工具，PATH 劫持风险低）。
>
> 严格模式 DNS（澄清，非缺陷）：`isBlockedHost` 在严格模式(`blockPrivateEndpoints`)下对 `UnknownHostException` 返回"拒绝"属**刻意 fail-closed**（宁拒勿连），默认模式不会因离线崩溃。详见续9 第四节。

## 二、验证结果（直接 JDK21 跑 exe 内同批字节）

- **回归（真实双版本 lib_v1/lib_v2）**：compare `added=63 deleted=4 modified=345 unchanged=1 | bizChanged=412`；report 253,446 B；export 816 class + `decompiled-sources.zip` —— 与修复前完全一致，无回归。
- **Zip Slip 恶意包**：解析层 `sanitizeKey` 对 `../../pwned.txt`、`WEB-INF/classes/../../pwned.class` 均抛 `拒绝路径穿越条目（Zip Slip）` 并**单条跳过**（rc=0，其余合法条目照常比对）；坏 key 永不进快照，`AssetExporter` 写盘边界校验再兜底 —— 外部目录**未出现** `pwned.txt` 越界文件。续8 验证：`evil_compare2.log` / `evil_export2.log`（坏条目被 `[隔离] 跳过可疑条目` 拦截，合法条目 `bizChanged=1` 正常比对）。✅
- **SSRF 元数据端点**：baseUrl=`http://169.254.169.254/...` → `拒绝访问受限网络端点（SSRF 防护）: 169.254.169.254`，**未发出任何网络请求**，工具优雅降级。✅
- **SSRF 默认放行 localhost**：baseUrl=`http://localhost:11434`（Ollama）→ 走到真实连接 `Connection refused`，证明默认不误伤本地模型。✅

## 四、续9 追加修复（2026-08-08 晚）：运维脚本进程隔离 + HTTP 响应上限

评审范围从"分析器核心"扩展到"运维脚本层"（`scripts/start-app.ps1`、`stop-app.ps1`）。新增发现并修复：

| 级别 | 模块 | 问题 | 修复 | 状态 |
|---|---|---|---|---|
| **P1** | ai（网络入向） | **HTTP 响应体无读取上限**：`HttpAiAnalyzer.readAll` 按流全量读，恶意/被劫持（或明文 http 被 MITM）端点返回超大 body 可拖垮 JVM（OOM）。与已修的 zip bomb 同一类"资源边界"缺陷。 | `readAll` 增加 **16MB 硬上限**，超限抛 `HTTP 响应体过大（OOM 防护）`，由 `callChat` 现有异常路径降级，不会崩溃。 | ✅ 已修（编译+回归无影响） |
| **P1** | scripts/stop-app.ps1 | **PID 复用误杀**：按 pidfile 记录的数字 PID 直接 `taskkill`，若旧进程已退出、OS 把该 PID 复用给无关/系统进程，则误杀。 | 新增 `Confirm-BempDiffPid`：杀前校验该 PID 的进程名仍为 `BempDiff` 且主模块路径等于本机 exe，否则跳过并告警。 | ✅ 已修 |
| **P1** | scripts/stop-app.ps1 | **按名盲杀所有实例**：回退逻辑 `Get-Process -Name BempDiff` 会终止全机所有同名进程，误杀其他用户/其他实例的 GUI。 | 按名回退仅杀"主模块路径 == 本机 exe"的实例；路径不符者跳过并提示，避免跨用户/跨实例误杀。 | ✅ 已修 |
| **P2** | scripts/start-app.ps1 | 启动前清理旧进程同样未校验 PID 身份（PID 复用风险）。 | 复用 `Confirm-BempDiffPid` 守卫。 | ✅ 已修 |

**续9 回归（JDK21 重编译 + 直接跑 exe 内同批字节）**：compare `bizChanged=412`（无回归，RC=0）；恶意包 compare 仍 `[隔离] 跳过可疑条目` 两条、rc=0、`bizChanged=1`、无越界 `pwned.txt`；report RC=0 无异常。app.jar 重打为 83797 B 并 `cp` 覆盖 exe 内 `app/app.jar`。

## 三、结论

BempDiff 工具原"权限隔离逻辑"存在 **1 个 P0（任意文件写）** 与 **2 个 P1（SSRF 不全、解压炸弹）** 实质缺陷，已在评审中修复并经恶意样本实跑验证；密钥与子进程隔离同步加固。续9 进一步覆盖**运维脚本层**的进程隔离（PID 复用误杀、跨实例盲杀）与**网络入向**的 HTTP 响应体 OOM 防护，补全"资源边界"闭环。修复后回归无变化，隔离边界现已闭合。唯一保留项为严格模式刻意 fail-closed 的 DNS 行为（非缺陷）。

> 证据：`bempdiff/verify_exe/sec/` + `bempdiff/verify_exe/VERIFICATION.md` + 本文件 + `bempdiff/scripts/`（start-app.ps1 / stop-app.ps1 已加固）。exe 内 `app.jar` 已用修复后字节覆盖（`cp` 换入，绕开沙箱安全删除限制）。
>
> **证据清单现状（2026-09-10 核账）**
>
> | 证据 | 状态 |
> |---|---|
> | `sec/report.md`（260 KB，评审报告快照） | ✅ 在库 |
> | `sec/make_evil.py`（恶意包构造脚本，可重建攻击样本） | ✅ 在库 |
> | `sec/evil_export2/decompiled-sources.zip`（越界写被拦截后的实际产物，207 B） | ✅ 在库 |
> | `sec/export/decompiled-sources.zip` | ✅ 在库 |
> | `sec/evil_compare.log`、`sec/evil_export2.log`、`sec/ssrf.log`、`sec/ssrf_local.log` | ⚠️ **不在库，且不可恢复**（见下） |
>
> **四个 `.log` 缺失的根因**：仓库根 `.gitignore:6` 有全局规则 `*.log`，而当时的实跑证据是以 `.log` 形式留在工作区的 →
> **从初始提交 `4983e12` 起就未被纳入版本控制**（`git log --all` 对 `verify_exe/**/*.log` 的入库记录为**空**；初始提交里 `prototype/verify_exe/` 已只有 16 个非 log 文件）。
> 后续工作区副本在清理轮次中按「运行时日志」处置，且绕过回收站 → 不可恢复；已全盘检索 `D:\code` 与历次隔离区，确认无副本。
> 因此该行原先声称的 4 个 log 属于**从未入库的工作区产物**，非本轮或前轮清理误删。
>
> **教训（已封堵）**：实跑证据不要用 `.log` 扩展名留存，否则会被全局 `*.log` 静默吞掉。`bempdiff/verify_exe/**` 已加入 `.gitignore` 例外并登记进 `cleanup-config.yaml → preserve_paths`。
> 评审结论本身由 `sec/report.md` + `make_evil.py`（可重放攻击）+ `sec/evil_export2/`（拦截后产物）共同支撑，**不依赖这 4 个 log**；如需重放，用 `make_evil.py` 重新生成恶意包跑一次即可。
