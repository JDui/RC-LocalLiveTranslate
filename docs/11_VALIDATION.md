# 0.1.0 验证记录

日期：2026-10-09 至 2026-10-12（Asia/Shanghai）。当前源码为 Android 0.1.0-prealpha.2；各阶段旧数据明确保留，不将旧版本检查当作新制品证据。

## GUI 基准工具构建（未随 prealpha.2 发布）

2026-10-12 新增独立 Macrobenchmark 及三个设备用例。完整 Debug / Release / 普通测试 / lint / 设备测试 APK / benchmark target lint / benchmark 双 APK 命令 BUILD SUCCESSFUL in 2m 28s；现有普通报告仍为 146 项、0 failures / errors / skipped，不把缓存命中当作重新执行。主用户保护首次使用非公开 SDK 的 `UserHandle.of` 导致编译失败，改为公开的 `getUserHandleForUid` 后工具重编译 BUILD SUCCESSFUL in 17s，实际执行 compileBenchmarkKotlin。任务列表确认 `connectedBenchmarkAndroidTest` 存在，但未执行设备测试。

三个实际 APK 的包名 / Manifest 隔离检查、正式版与被测包非调试 / 无联网和广泛存储权限、仅 benchmark target profileable、六个应用原生库与两个工具库 ARM64 ELF / ZIP 16 KB 均 PASS。共享 ELF 校验器在已发布签名 prealpha.2 APK 上回归通过；8 个内存合成检查通过，分别记录，不加入 JVM 或设备测试数量。初版 Perfetto 1.0.0 的 4 KB 问题已通过固定全套 1.0.1 修复，最终 APK 验证通过。

没有连接 Android 真机；三个 GUI benchmark 没有启动耗时、帧耗时或旋转恢复的实测结果，主用户门禁同样未运行。方法和隐私边界见 17_BENCHMARK.md。此段是本地证据；新增 CI 结果须以实际运行记录为准，不引用 prealpha.2 的旧绿色检查证明新工具。

实现提交 `6e3d5da` 的 [远程运行 38213770530](https://github.com/JDui/RC-LocalLiveTranslate/actions/runs/38213770530) 已完成、结论 success。主机 `:app:testDebugUnitTest` 实际执行，无 UP-TO-DATE 标记；Gradle BUILD SUCCESSFUL in 6m 35s、219 项任务实际执行。正式 / 目标 / 工具 Manifest 隔离、Perfetto ARM64 ELF、工具 APK ZIP 与 8 项合成校验均 PASS，两个 benchmark APK 已上传为 CI 附件。整个作业 10m 58s，不把它当作手机性能。CI 提示 action Node runtime / setup-java v4 弃用及 ubuntu-latest 后续镜像迁移，当前不是失败；未隐藏这些提示。公开 prealpha.2 的标签和附件保持不变，所有设备门禁仍未关闭。

## prealpha.2 恢复与 tabletop 回归

2026-10-12 新增 tabletop Models / Sessions、延迟记录载入恢复、生产 MainActivity 重建共 4 项主机回归。普通总数 146，0 failures / 0 errors / 0 skipped；完整 Debug / Release / 普通测试 / 两类 lint / 设备测试 APK 命令 BUILD SUCCESSFUL in 2m 2s，普通测试实际执行。Debug / Release lint 各 0 errors / 14 warnings：原有 4 项提示之外，远程版本查询增加 10 项 Gradle / AGP / AndroidX 依赖更新通知，没有新增代码正确性警告，不在此修复版升级依赖或抑制 lint。

定向回归先受沙箱外 Kotlin daemon 缓存权限影响而编译失败，改用获准的本机工具权限后运行。浅滚动第 5 行首次断言通过但不足以证实恢复；加强为第 15 行后旧实现失败 1 / 2 项，第 15 行不再可见。修复仅将 detailState 的空载入 key 回退到已保存的 selectedId；同一两项测试随后 BUILD SUCCESSFUL in 40s，位置误差要求不超过 1 px。生产 MainActivity / Room 的 ActivityScenario 重建通过，确认新旧 Activity 实例不同、导航 / 选中 / 阅读及返回保留。不是设备进程回收或真实录音证据。

tabletop 新截图 `models-tabletop.png`、`models-tabletop-import.png`、`sessions-tabletop-selected.png` 已人工检查；导入入口 / 记录 / 导出在各自安全区内，独立滚动可达。截图仍为 API 35 Android Canvas / Compose 主机渲染，无实际模型数据，不是 OEM 悬停或 SAF 测试。

签名候选 `RC-LocalLiveTranslate-0.1.0-prealpha.2-arm64.apk` 为 82,174,797 bytes，约 78.4 MiB。签名 v3、versionCode 2 / versionName 0.1.0-prealpha.2、minSdk 28 / targetSdk 36、唯一 ARM64 ABI、非调试及无联网权限经最终 APK 核对。与第一版比较的是签名证书身份，以确认覆盖升级兼容；没有比较普通源码文件哈希。六库 ELF / ZIP 16 KB、无禁用导出、必需 JNI 和 SOURCE 检查通过。第一版 APK / 标签不覆盖；第二版的公开状态及远程结果以实际记录为准。

实现提交 `37aa8b3` 的 [远程运行 38203778203](https://github.com/JDui/RC-LocalLiveTranslate/actions/runs/38203778203) 已完成、结论 success。日志确认普通主机测试实际执行，BUILD SUCCESSFUL in 2m 56s，原生必需入口 / 禁用导出 / SOURCE 和 APK ZIP 16 KB 检查 PASS；设备测试仍仅构建。获取运行日志首次遇到网络 EOF，重取成功，未重新触发或假冒构建。

2026-10-12 12:43（Asia/Shanghai）[v0.1.0-prealpha.2](https://github.com/JDui/RC-LocalLiveTranslate/releases/tag/v0.1.0-prealpha.2) 已公开。API 核对非草稿、预发布、targetCommitish 为 `37aa8b379aef4ae91bf3a0ca7fc597ea2d8b77e1`，唯一 APK 附件 uploaded、82,174,797 bytes，正式版本下载路径有效。第一版保持不变。真实设备、冷启动杀进程、准确率、折叠体验及热机门禁仍未关闭。

## prealpha 候选最终复核

2026-10-12 完整命令 `./scripts/build.ps1 -Tasks @('assembleRelease','assembleDebug','testDebugUnitTest','lintRelease','lintDebug','assembleDebugAndroidTest','--no-daemon','--max-workers=2')` 为 BUILD SUCCESSFUL in 55s。142 项普通测试 XML 均为 0 failures / 0 errors / 0 skipped；本次测试任务 UP-TO-DATE，报告实际执行时间为 2026-10-09 20:32，不计为重新执行。新增设置版本回归曾因遗漏测试 context 引用编译失败，修复后报告中的 29 项 ComposeUiTest 全部通过。Debug / Release lint 各 0 errors / 4 原有 warnings。

`package-prealpha.ps1` 生成 `RC-LocalLiveTranslate-0.1.0-prealpha.1-arm64.apk`，82,174,797 bytes（约 78.4 MiB）。apksigner 验证通过，当前 API 28+ 制品使用 APK v3、一个 RSA 3072 位签名身份。aapt 确认 applicationId、versionCode 1 / versionName 0.1.0-prealpha.1、minSdk 28、targetSdk 36、唯一 ABI arm64-v8a，无 application-debuggable；权限没有 INTERNET / ACCESS_NETWORK_STATE / 广泛存储项。

检查的是最终签名 APK 本体：六库 ELF64 PT_LOAD 和 ZIP 16 KB 对齐通过；无 TTS / 离线 diarization 导出、必需 ASR / VAD / embedding JNI 入口和固定源码 / 核心许可清单通过。签名材料及 APK 位于 Git 忽略的 `.tools`；Windows 签名目录 ACL 仅当前用户与 SYSTEM、均非继承权限。没有打印或发布口令。ADB 设备列表为空，设备测试仍仅构建，真实模型 / OEM / 热机门禁未关闭。

公开 Release 与远程 CI 的状态以实际发布和运行记录为准；本节仅记录已完成的本地主机、构建和签名检查。

### 首次远程 CI

2026-10-12 推送 `4b96d34` 后的 [运行 38200164627](https://github.com/JDui/RC-LocalLiveTranslate/actions/runs/38200164627) 在 SDK 安装步骤失败：setup-android v3 默认请求 `tools`，sdkmanager 报 `Failed to find package 'tools'`。原生准备、Gradle 和测试均未执行，不计为编译或测试失败 / 成功。按 [安装动作上游说明](https://github.com/android-actions/setup-android#the-deprecated-tools-package) 显式设置 `packages: platform-tools`，保留固定 NDK / CMake；修复的远程结果另行核对。

修复提交 `9e0e160` 的 [运行 38200367387](https://github.com/JDui/RC-LocalLiveTranslate/actions/runs/38200367387) 已完成、结论 success：Linux SDK 安装、固定无 TTS 原生准备、Debug 编译 / 普通主机测试 / lint / 设备测试 APK 构建、APK 原生检查和制品上传均通过。Gradle 日志为 BUILD SUCCESSFUL in 5m 33s，testDebugUnitTest 实际执行；六库 ELF / ZIP 16 KB、禁用导出及必需 JNI / SOURCE 检查逐项 PASS。与标签相比只修改 CI 和验证文档，应用源码未变；这仍不是设备端 instrumentation 或实际权重推理证明。

远程另有非失败提示：部分 Actions 的 Node 20 入口由托管服务强制使用 Node 24，setup-java v4 已弃用；ubuntu-latest 公告将迁移到 Ubuntu 26。这些不隐藏、不当作本轮失败，也不据本轮通过承诺未来托管环境不变化。

### 公开 prealpha

2026-10-12 11:46（Asia/Shanghai）[v0.1.0-prealpha.1](https://github.com/JDui/RC-LocalLiveTranslate/releases/tag/v0.1.0-prealpha.1) 已公开。GitHub API 核对 isDraft=false、isPrerelease=true，标签 / targetCommitish 为 `4b96d349c4144df14cb9f7cc951cc3c27cf1ae7e`，唯一 APK 附件为 uploaded、82,174,797 bytes，下载路径使用正式版本标签。模型、音频、签名私钥 / 口令未上传。公开测试交付已完成，但项目整体设备、准确率、OEM、热机和完整合规门禁仍未关闭。

## prealpha.1 已完成

| 项目 | 结果 | 证据 |
| --- | --- | --- |
| 拉取仓库 | PASS | main 跟踪 origin/main，原仓库只有架构文档 |
| Debug APK | PASS | `assembleDebug`，`app/build/outputs/apk/debug/app-debug.apk`，99,969,495 bytes（约 95.3 MiB），包含实际 ARM64 推理库但不含权重；旧阶段大小保留在各阶段记录 |
| 单元及主机 UI 测试 | PASS | 142 tests，0 failures，0 errors，含折叠功能双栏、窗口调整 / 状态恢复、真实 JVM 重采样、压力策略 / 取消、字段级隐私设置、诊断统计、启动器图标、模型租用、说话人历史合并 / 近期拆分、长许可分块、安装版本显示及合成后端管线验证 |
| Android lint | PASS | 0 errors，4 warnings，另有 Kotlin Gradle 未来弃用提示 |
| APK 元信息 | PASS | applicationId `com.jd.rc.localtranslate`，version 0.1.0-prealpha.1，minSdk 28，targetSdk 36，ARM64 |
| APK 权限 | PASS | 麦克风、前台服务、麦克风前台服务、通知，以及 AndroidX 私有 receiver 权限；无 INTERNET、ACCESS_NETWORK_STATE 或广泛存储权限 |
| 原生 UI 渲染 | PASS | Robolectric API 35 native graphics；非空像素检查与截图人工检查 |
| 推理运行库打包 | PASS | sherpa JNI / ONNX Runtime、Foxlet / libc++ 随 APK 打包；只证明打包，不证明 Android 加载 |
| 16 KB 静态对齐 | PASS | 六个 ARM64 `.so` 的 ELF64 PT_LOAD、最新 APK 的 `zipalign -c -P 16 4` |
| 设备测试 APK | BUILD ONLY | `assembleDebugAndroidTest` 已生成；没有连接设备，三个测试未运行 |
| 原生准备与工具脚本 | HOST / BUILD PASS | 固定无 TTS JNI 编译 / 准备通过；五套实际权重已在忽略目录打包并通过生产导入 / 租约测试，未分发，未运行 Android 模型 |
| 热 / 低内存保护 | HOST PASS | 分档、30 秒稳定恢复、临界任务取消、可选 worker 空闲释放及 API 28 内存回退；不是实际 native 释放或热机证明 |
| 有界运行诊断 | HOST PASS | JSON / CSV、单调计时、来源、样本窗口、队列计数与 PSS / RSS 采样回归；不是实际权重性能报告 |
| 翻译魔芋图标 | HOST PASS | 自适应前景 / 底色 / 单色层，API 28 圆形 48 / 192 px 内容渲染；不是 OEM launcher 验收 |
| 功能双栏与连续性 | HOST PASS | 四页双栏、记录选中 / 阅读跨模拟窗口尺寸变化、可保存状态恢复及紧凑详情返回；不是 OEM 真机开合、输入法或真实音频连续性验收 |
| 补丁格式 | PASS | `git diff --check` |

命令为 `./scripts/build.ps1 -Tasks @('assembleDebug', 'testDebugUnitTest', 'lintDebug', 'assembleDebugAndroidTest', '--no-daemon', '--max-workers=2')`。原始骨架为 22 项测试；折叠屏阶段为 36 项，原生管线阶段为 56 项，音频前端阶段为 76 项，系统压力 / 设置阶段为 99 项。本轮加入有界运行诊断、管线计时与 DSP 采集计数、折叠导出弹窗及翻译魔芋启动器回归，没有真实 Android 模型推理或准确率证据。

首次包含图标的完整命令为 `BUILD SUCCESSFUL in 2m 7s`，XML 共 116 tests / 0 failures / 0 errors；补齐独立弹窗截图后再次完整构建通过。APK 重新执行 `aapt dump permissions` 和原生对齐脚本：无 INTERNET / ACCESS_NETWORK_STATE，六个 ARM64 ELF 与 APK ZIP 静态对齐全部通过。ADB 当前列表为空，设备端三项测试未执行。最终资源归并后的结果见下方本轮最终复核。

诊断阶段最初新增管线测试在普通 JVM 运行时调用 Android JSONObject stub，失败 1 项；已将该类切换到 API 35 Robolectric，再运行通过。不能把这次失败计作通过。图标初版的 v26 目录冗余和该配置缺 monochrome 触发两项额外 lint，随后三个图层归并到 minSdk 28 可直接使用的 mipmap-anydpi，未添加 lint 抑制。

### 图标阶段最终复核

资源归并后第一次 AAPT 增量构建报 `mipmap/ic_launcher not found`，虽然新的源文件和 merger 登记存在，compile-file-map 仍登记旧 v26 / v33 路径且编译输出缺失。针对 mergeDebugResources / processDebugResources 执行 `--rerun-tasks` 后资源构建通过，未删除整个工程缓存。随后最终完整命令为 `BUILD SUCCESSFUL in 1m 59s`，116 tests / 0 failures / 0 errors，lint 恢复 0 errors / 原有 4 warnings。最新 APK 101,424,201 bytes（约 97 MiB），再次通过六个 ARM64 ELF 和 APK ZIP 的 16 KB 静态对齐；权限仍无 INTERNET / ACCESS_NETWORK_STATE。ADB 无设备，三个设备测试仍仅编译。主机截图已人工检查，详见截图范围。

### 功能双栏阶段最终复核

用户追加「接近小红书的折叠适配程度」后，四页增加真实内容双栏，并保留紧凑单栏。第一轮 116 测试有 3 项失败：旧测试仍套 FoldSafePage，导致新的第二栏被重复裁切，省电触控没生效 / 导出按钮不可见；记录已呈现列表和详情，旧文本选择器匹配两处标题。测试壳与生产入口同步移除重复限制，点击改为带会话描述的列表项，诊断几何断言更新为右栏。随后新增回归达 127 项且全部通过。

追加录音命令回归时遗漏 RecordingService import，单元测试编译失败；补齐后最终完整命令 `BUILD SUCCESSFUL in 1m 14s`，128 tests / 0 failures / 0 errors，lint 0 errors / 4 原有 warnings。再次验证六个 ARM64 ELF / APK ZIP 16 KB 对齐和无联网权限。APK DEX 中确认存在 FoldContentLayout 与 live-companion，不只验证主机编译类。没有普通文件哈希比较。ADB 仍无设备，设备测试 APK 只编译。

本阶段新增 4 个几何测试及 8 个主机 UI 测试：平屏展开分区、book 模型两栏、实时伴随区、tabletop 设置、选中记录和阅读位置跨窗口变化、SaveableState 恢复 / 系统返回、200% 字体隐私与性能独立、模拟录音状态的姿态变化不重复提交命令。未调用真实 AudioRecord 或 JNI。详细证据强度与缺项见 13_ADAPTIVE_ACCEPTANCE。

临界取消回归曾失败：同步 ASR 返回后，缓冲通道读取未挂起，取消的 worker 仍取走两份积压任务。已在循环和原生调用返回处显式检查取消；同一回归现确认只完成已进入的调用、拒绝新输入，待其退出后才关闭引擎。它使用受控 fake 同步调用，不证明 Android JNI 能被强制中断。

新增隐私时序测试最初在暂停的 Robolectric Main Looper 上无限等待写入。线程栈确认卡在测试自身的 `runBlocking` 等待后，仅停止了该项目的测试执行器；测试改为显式驱动主循环并限制等待 5 秒，随后完整构建通过。这个中止不计作通过。当前测试证明页面 StateFlow 尚未刷新时，关闭记录的提交已使保存门禁拒绝保存；IO 读取失败的拒绝分支尚未做存储故障注入。

Windows Gradle 8.11.1 首次转换依赖时多次出现 `Could not move temporary workspace ... to immutable location`。停止对应 daemon 后，仅将日志指明且已验证属于工作区的三处生成缓存移至其目标位置，未清理源码、依赖或整个缓存；此后构建继续成功。权限 lint 和 JUnit 测试返回类型问题均已修正，未新增 baseline 或抑制错误。

APK 检查发现 Media3 common 会自动合并 ACCESS_NETWORK_STATE；主 manifest 显式移除这项不需要的权限，并新增主机合并清单及设备 smoke 检查。INTERNET 保持不申领；仅有删除标记但没有上游声明的多余节点已去除，避免制造 manifest 合并警告。

### 真实翻译模型准备阶段

2026-10-09 从 Foxlet v0.5.0 固定目录下载 Mozilla 英中 2.2 / 日英 2.1 的 7 个真实权重 / 词表文件，与上游大小及 SHA-256 全部一致；固定提交的 MPL-2.0 许可原文及 SOURCE 随本地 ZIP 保留。总原始权重 / 词表 106,704,172 bytes，生成包分别 37,157,512 / 37,315,912 bytes。本地缓存离线重验通过，11 项打包脚本检查通过。

显式真实模型测试 `PreparedModelImportTest` 使用生产导入器及 Foxlet 文件解析器，在 API 35 Robolectric 上通过：1 test / 0 failures / 0 errors / 0 skipped，Gradle BUILD SUCCESSFUL in 37s。确认英中分离词表与日英共享词表、固定元数据、许可 / SOURCE 及删除；未调用 Android JNI，不能称为实际翻译推理通过。XML 副本保存在忽略目录。普通回归默认排除此类，不依赖这些本机权重。详见 14_MODEL_PREPARATION。

随后普通回归 `BUILD SUCCESSFUL in 49s`：128 tests / 0 failures / 0 errors / 0 skipped，XML 中不含真实权重测试类。不是把缺权重测试 skip 后合计为通过。此轮未改应用功能或重建 APK；上一阶段 APK / lint / 原生对齐证据仍按原日期和范围记录。

### 语音模型准备与真实主机检查

在忽略目录准备三个语音包，与已准备的两翻译包组成五包基线。ASR / VAD 采用 GitHub 官方资产摘要；CAM++ 无发布者摘要，明确记录从官方 HTTPS 制品观察到的大小 / SHA-256。固定许可原文、作者 / 来源及摘要依据保留。五包最终权重 / 词表共 375,178,899 bytes，不加入 APK 或 Git。

首次选定的 2025-09-09 粤语微调候选实际加载并可运行，但在预设三段本地 Windows TTS 的 auto 检查上全部标成 yue，CLI 非零退出，失败报告保留。相同样本 / auto / ITN=true / 16 kHz / CPU 2 threads 下，2024 原版返回 en / zh / ja 及可对应原句的文本，因此本地默认基线改为 2024。没有通过手动指定语种或降低断言来消除失败，见 ADR-0003。

Windows x64 sherpa-onnx 1.13.8 / numpy 2.2.6 基础 native 检查 6 项（两轮加载 / 静音 VAD / ASR 返回结构 / 192 维有限非零 speaker 向量）通过；再加三语本地 TTS 标签和独立 VAD，共 9 项通过。ASR 是整文件，不是生产 5 秒语段管线。Python 主机脚本最初误用不存在的 `get_result_as_json` 失败，读取实际 API 并修正为 `stream.result` 后通过。没有真人语料、CER / WER、speaker 身份或 Android 性能结论。

最终 2024 基线的真实生产导入 / 租约测试：2 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESSFUL in 36s，执行 11.551 秒；确认五包元数据 / 许可 / 摘要、三语音必需文件、占用拒绝替换 / 删除和释放后删除。XML 副本在 `.tools/models/speech/validation/`。模型打包 / 缓存安全脚本 17 项通过，PowerShell 11 份脚本语法检查通过。Android 设备仍无连接，本轮未改应用功能或重建 APK，静态 APK / lint 仍引用原阶段证据。

随后最终普通回归 BUILD SUCCESSFUL in 50s：128 tests / 0 failures / 0 errors / 0 skipped；真实模型类默认排除。被拒 2025 包与默认包目录隔离在 rejected-packages，不删除失败证据。

## 测试范围

### 说话人历史修订阶段

先运行两个核心类的 19 项定向测试，BUILD SUCCESSFUL in 52s。追加实际 tracker → ConversationStore 的长历史合并回归后，完整 `assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest --no-daemon --max-workers=2` 为 BUILD SUCCESSFUL in 1m 40s：137 tests / 0 failures / 0 errors / 0 skipped，lint 0 errors / 4 原有 warnings。新 APK DEX 确認包含 SpeakerReconciliationPolicy；六个 ARM64 ELF 和 APK ZIP 16 KB 静态对齐通过，权限无 INTERNET / ACCESS_NETWORK_STATE。ADB 无连接设备，三个设备测试仍仅编译。

验证涵盖默认规则下漂移簇拆分、收敛簇合并、退休标签不复用、超出 256 份证据窗口的旧字幕合并、单调 speaker revision、重复 / 非有限时长拒绝、维度和证据上限、清理、延迟 / 早到标签、别名链和循环拒绝、错误会话隔离、文本 / 翻译 / 样本时钟保持。这些是合成向量和软件事件证据，不是 CAM++ 真人身份或性能结论。

公开 prealpha 准备阶段发现官方 sherpa Android 构建默认开启 TTS，带来额外许可边界。该问题已通过固定源码关闭 TTS / 离线 diarization 的构建处理；上面 137 项阶段的 APK 仍使用原官方库，新的打包证据见下节。

### 无 TTS 发行准备阶段

NDK 29.0.14206865 / CMake 3.22.1 / Clang 21.0.0 从固定 sherpa 提交完成 ARM64 API 28 JNI 编译，原生库剥离后 3,531,008 bytes；26 份原生传递组件全文 / 版权 / NDK / SOURCE 材料随 SDK 保留，Foxlet AAR 校验官方发行摘要。TTS / 离线 diarization OFF，speaker embedding 保留。

初次重新准备与另一个构建启动重叠，旧 sparse-checkout set 暂时移除了正被编译的头文件，原生重试失败；另一个重复准备被主动中止。这两次不计成功。改为 additive sparse-checkout 并增加互斥锁后，串行重新准备成功，不重置源码 / 依赖缓存。首轮独立 268 目标编译和后续串行重试均完成。

增加长许可分块及 LazyColumn 后完整构建 BUILD SUCCESSFUL in 1m 39s：141 tests / 0 failures / 0 errors / 0 skipped，lint 0 errors / 4 原有 warnings。APK 本体的六个 ELF / ZIP 16 KB 对齐、无 TTS / 离线 diarization 导出、三个必需 JNI 入口和 SOURCE / 核心许可文件检查通过；没有 Android 加载或实际模型执行证据。首轮 AGP 提示无法再次剥离已剥离的 JNI，随后 app 明确固定相同 ndkVersion，最终复核另记。

固定 ndkVersion 后最终完整命令 BUILD SUCCESSFUL in 1m 29s；单元测试为 UP-TO-DATE，保持前轮实际执行的 141 项 / 0 失败 / 0 错误 / 0 跳过，不算作重新执行。原生打包重新执行，未再出现该 strip 提示；lint 0 errors / 4 warnings。最新 APK 123,691,147 bytes（约 118 MiB），复查本体六库 ELF / ZIP 16 KB、无禁用导出 / 必需 JNI / 源码许可清单全部通过，权限无联网项。12 份 PowerShell 语法及 git diff --check 通过。CI 已更新固定 NDK / CMake 与相同静态检查，尚未推送执行。

- SubtitleReducerTest：8 项。会话隔离、旧 revision 拒绝、独立 speaker revision、语言切换、未知说话人修正、早到事件合并、旧译文失效、中文不重复翻译与采样时间导出；新增全历史合并保持原文字段、别名链 / 循环 / 延迟结果、ConversationStore 旧会话拒绝和别名重置。
- ModelImportTest：6 项。真实文件完整性校验、失败替换保留旧模型、删除、ZIP 越界路径及非法路径拒绝、使用期间禁止替换/删除、缺运行文件拒绝租用；Foxlet 精确哈希集合排除 LICENSE 并去重共享词表。使用 `abc` 测试文件，不是模型权重，不证明 ONNX 可加载。
- PreparedModelImportTest：2 项，默认排除，显式 `-PverifyPreparedModels=true` 才运行。实际 Mozilla 翻译包校验 / 独立词表、五套真实基线包安装和必需三语音模型租约保护，缺包会失败；只证明生产文件导入路径，不调用 ARM64 JNI。与普通 141 项分别记录。
- LicenseTextTest：4 项。空 / 短文本、1 MiB 以上多行声明、十万字符单行、UTF-16 surrogate 边界，确认分块有界且拼接内容不变；实际文件读取保持原文，设置弹窗仅布局可见分块。
- LocalDataTest：9 项。字幕 JSON 完整往返、Room 保存/重命名/删除、DataStore 跨 Repository 持久化、记录保留开关、合并清单保留麦克风但无 INTERNET / ACCESS_NETWORK_STATE；省电模式持久化、并发字段变更不恢复记录保留、显示变更保留隐私并限制字号范围、页面仍显示旧值时关闭记录门禁已生效。
- ComposeUiTest：29 项。9 个尺寸/主题/页面渲染、页面导航和设置开关交互、历史详情与三种导出选择；book、tabletop、双屏设置页、折叠弹窗及系统姿态更新保留阅读位置；离线打开 SDK SOURCE；折叠设置页的性能控制和诊断避让、200% 字体下压力提示不遮住停止入口；诊断 JSON / CSV 选择和取消、弹窗位于铰链左区。另有本阶段上述 8 项功能双栏 / 连续性回归及设置读取安装包版本的检查。
- FoldLayoutTest：13 项。普通窗口、非遮挡折痕、竖向 / 横向分隔、FULL 遮挡、系统安全区偏移、窄区域回退、窗口外特征、多铰链安全区域；扣除导航后的平屏双栏、紧凑 / 矮窗口回退、实体铰链不重复分栏、大字体宽度回退。
- SpeakerTrackerTest：12 项。证据确认与 unknown 回填、A-B-A 及跨语言独立性、短句/NaN/零向量拒绝、歧义拒绝、尺度归一化/维度检查、speaker 上限；新增重复 / 非有限时长、默认拆分、默认合并 / 退休标签、容量 / 单模式拒绝拆分、1024 份证据有界 / 修订 / 清理、实际 tracker 接入旧历史合并。不是实际声纹模型效果。
- RealtimePipelineTest：18 项。合成 VAD / ASR / embedding / translation 后端连接、最终事件、暂停恢复、缺翻译模型、断帧绝对时间、旧会话拒绝、固定内存环、暂停尾窗、致命错误通知、同步推理完成后释放与并发停止；连续样本位置上的输入切换仍冲刷 VAD，采集诊断不取得字幕事件 monitor；原文优先、中度热状态保留翻译、声纹 / 翻译在进行中时不释放及空闲恢复、临界停止只通知一次、取消积压但等待同步调用；合成 EN / JA 调用、队列与完成报告，不包含文本。此测试类不加载 JNI 或权重。
- AudioFrontendTest：18 项。实际 Media3 SonicAudioProcessor JVM 实现的 16 kHz 位精确直通、48 / 44.1 kHz 正弦音调与时长、输入分块一致性、8 kHz 升采样及尾帧、两分钟 44.1 kHz 长度无累计偏差；累计有理数时钟、不规则分块逐样本保留、路由边界、原始丢帧不插值、队列满非阻塞拒绝、边界标记延续、worker 失败和重复关闭、格式回退 / 失败释放、权限失败不重试、断连恢复预算；48 kHz 采集样本 / 输入队列及 DSP 计时与 16 kHz 输出对应。微型麦克风对象为 fake，不调用设备 AudioRecord。这不是真实语音准确率、抗混叠质量或热机性能证据。
- PerformancePolicyTest：8 项。缺失监测值、中度 / 严重 / 临界热状态、OS lowMemory / 系统内存 / 堆余量分档、30 秒稳定恢复、波动重置、省电策略不能绕过保护；数值是待校准参数。
- RuntimePressureMonitorTest：3 项。Robolectric API 35 模拟热状态回调和移除监听、API 28 无热 API 时轮询内存、设置改变与关闭后不再发布。不是实际设备传感器证据。
- RunDiagnosticsTest：12 项。单调计时 / 毫秒与 RTF、成功分位数、失败与取消计数、有限窗口 / 全期计数 / 采样峰值、不同输入率累计时长、队列观察计数、JSON / CSV 转义与公式前缀、VmRSS 解析和溢出拒绝、结束时长稳定、未注册输入不编造 RTF、API 28 不编造热状态、API 35 系统热值与未激活的压力策略分离。全部为主机合成时钟 / 系统 shadow，不是性能实测。
- LauncherIconTest：2 项。Manifest 图标入口、透明 PNG 内容、API 35 monochrome 层及 API 28 的 48 / 192 px 圆形裁切非空渲染。图标资产实际由内置生成工具生成；不是 launcher / 壁纸主题的实机证明。

设备测试尚未运行：NativeRuntimeSmokeTest 两项（sherpa 版本/加载/无联网权限，Foxlet 初始化/释放/重建）；NativeModelTest 一项（应用内已审核模型与私有目录公开英语 WAV 经生产 DSP / VAD / ASR / 翻译，支持不同输入采样率）。测试前提和执行入口见 10_DEVELOPMENT。

截图使用生产 Compose 组件在 Robolectric Android 35 上绘制，包含固定演示字幕。截图通过 decorView / Android Canvas 捕获；不是设计稿、WebView 或真实设备 PixelCopy 截图。每张检查尺寸与像素颜色数量，并人工核对排版。

截图目录：`app/build/ui-screenshots/`。

| 文件 | 状态 |
| --- | --- |
| live-light.png | 412 × 915 dp，浅色 |
| live-dark.png | 412 × 915 dp，深色 |
| live-small.png | 360 × 640 dp，小屏，字幕可滚动 |
| live-landscape.png | 915 × 412 dp，侧栏 |
| live-tablet.png | 1280 × 800 dp，侧栏 |
| live-font-200.png | 200% 字体缩放，输入切换整行、导航标签分组换行 |
| models.png / sessions.png / settings.png | 模型、记录及设置页 |
| session-list-interaction.png | 记录详情交互前截图 |
| live-book-fold.png / live-tabletop-fold.png | 900 × 900 dp，分区字幕与录音控制 |
| settings-dual-screen.png / session-book-fold.png | 双屏实体遮挡的设置页及记录页 |
| settings-runtime-fold.png | 900 × 900 dp，显示 / 隐私在左区，省电切换 / 诊断与导航在右区 |
| live-pressure-font-200.png | 360 × 640 dp、200% 字体，实际录音 UI 状态中的原文优先提示与停止入口 |
| diagnostic-export-fold.png | 900 × 900 dp，JSON / CSV 格式弹窗在铰链左侧；叠加绘制独立 dialog decorView |
| launcher-konjac.png | API 28 Android AdaptiveIconDrawable，48 / 192 px 圆形裁切对照 |
| settings-expanded.png / live-expanded.png | 900 × 900 dp 平屏，扣除侧栏后的功能双栏 |
| models-book-expanded.png / sessions-expanded-selected.png | book 实体遮挡，语音 / 翻译或列表 / 所选详情双栏 |
| sessions-compact-selected.png | 在 900 dp 测试窗口内将 app viewport 收窄至 412 dp，保留 Record 2 Line 5；不是外屏实机截图 |
| settings-tabletop-expanded.png / settings-book-font-200.png | dark tabletop 分区与 200% 字体 book 设置 |

自动化 UI 证明组件和导航可工作，不等于系统 SAF 选择器、实际麦克风或真实窗口状态已验收。字幕和 Speaker 归属来自明确标记的演示文本；压力截图使用生产录音控件和合成 RecordingState，不是真的打开了麦克风。新增两张截图已人工检查，确认铰链避让和大字体下停止可达。

本轮诊断导出截图的第一版只捕获 Activity decorView，因此没包含独立 Dialog 窗口；测试语义断言虽通过，但不据此声称弹窗视觉通过。修正为叠加绘制最新 dialog decorView 后重新运行并人工检查，JSON / CSV / 取消均在左侧安全区。合成截图未复现系统窗口 dim / PixelCopy，只证明组件实际绘制和布局。启动器预览亦已人工检查，小尺寸主体可辨认且未被圆形裁切截断。

## 剩余 lint 提示

1. `AndroidGradlePluginVersion`：提示较新 Gradle 可用；本轮保留与 AGP 8.10.1 验证过的 Gradle 8.11.1。
2. `ChromeOsAbiSupport`：未提供 x86_64；产品当前范围为 Android ARM64。
3. `KaptUsageInsteadOfKsp`：Room 可迁移到 KSP，提高构建效率；当前 kapt 构建与测试已通过。
4. `WrongCommentType`：官方 sherpa Kotlin API 的示例块注释含 `@param`；保持上游源码不做局部改写。Kotlin 2.4.10 另提示 Gradle 8.11.1 将在未来版本退出支持，当前构建通过，未隐藏警告。

## NOT TESTED / NOT IMPLEMENTED

- 无连接的 Android 真机；未进行安装启动、麦克风占用、权限拒绝/撤销、通知停止、锁屏/后台及蓝牙路由实机验收。
- 折叠屏采用 WindowManager 官方测试发布器模拟姿态及 Compose 几何检查；未进行 OEM 真机铰链、键盘、旋转重建、持续录音期间折叠与多窗口调整验收。
- 已编译接入 Silero / SenseVoice / Foxlet / speaker embedding，但尚未用实际模型在 Android 上执行，没有真实 ASR、LID、翻译准确率或声纹分离结果。
- 16 kHz 外采样率重采样、音频输入切换和有限恢复已实现并通过主机回归，但实际 AudioRecord 格式支持、USB / 蓝牙路线、系统音频分享静音、恢复期间丢音及真实识别质量未验收；Sonic 不是带限 sinc 抗混叠验收结论。
- 热 / 低内存动态降级及性能档位已实现并有主机回归，但触发阈值、实际模型的内存峰值 / 释放 / 重载以及 30–60 分钟热机均未验收。长会话 speaker 全历史合并 / 有界近期拆分已实现，真人效果和更早语段的拆分重建没有证据。
- 五套真实基线包已通过主机生产导入器，三个语音模型已在 Windows x64 运行；Android JNI、损坏大模型、存储耗尽、系统 SAF 文档提供者和手机 native 加载仍未验收。
- 未进行 Android 9–17 设备矩阵、16 KB page size 实机兼容、飞行模式真实推理、PSS/RTF/延迟、30–60 分钟热机或电池测试。
- GitHub Actions 的主机 / 构建流程通过；没有 Android 设备测试证据。
- prealpha 已公开；未替仓库所有者指定源码许可证，未完成独立权重与题材权利审核。
