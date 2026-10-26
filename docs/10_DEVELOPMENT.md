# Android 0.1.0 开发说明

此版本已接通原生 GUI、采集、有界推理管线及本地数据管理。生产路径调用 sherpa Silero VAD、SenseVoice、speaker embedding 与 Hy-MT2 STQ；尚无 Android 实际模型运行证据，不能把“已接入”当作“已验收”。演示模式仍独立，字幕、译文和 Speaker 标签来自固定文本，界面与记录标注「演示数据」。

## 工程

JDK 17、Gradle 8.11.1、AGP 8.10.1、Kotlin 2.4.10、Compose BOM 2025.05.01、compile/target SDK 36、minSdk 28。版本固定在 `gradle/libs.versions.toml`。APK 仅打包 ARM64，包含 sherpa-onnx 1.13.8 JNI / ONNX Runtime 与 llama.cpp STQ JNI。SDK 准备目录 `.tools/native` 不提交 Git；不包含模型权重。

原生准备、模型打包及验证脚本使用 PowerShell 7（`pwsh`）。sherpa JNI 从固定源码构建，关闭 TTS / 离线 diarization，保留 VAD / ASR / speaker embedding。需要 Android SDK 内固定 NDK 29.0.14206865 与 CMake 3.22.1；不再直接使用开启 TTS 的官方 Android JNI。先安装固定工具并运行原生 SDK 准备脚本，再在 Android Studio 中打开根目录并同步，配置 JDK 17 与 SDK 36。命令行：

```text
sdkmanager "ndk;29.0.14206865" "cmake;3.22.1"
pwsh ./scripts/bootstrap-native.ps1
./gradlew assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
```

Windows 无现成工具链时可在项目内安装（所有下载与缓存位于已忽略的 `.tools`）：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/bootstrap.ps1
./scripts/build.ps1
```

Windows 构建脚本为 JDK Unix-domain socket 指定正常临时目录，避开此机器 TEMP 的 8.3 短路径问题。直接调用 Wrapper 时需使用正常临时目录，或同样配置 `jdk.net.unixdomain.tmpdir`。

APK：`app/build/outputs/apk/debug/app-debug.apk`。测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。原生组件截图：`app/build/ui-screenshots/`。

## 当前实现

- Live、Sessions、Settings 使用 Compose / Navigation Compose；Models 为 Settings 首项子页，运行与诊断为另一子页；ViewModel 暴露 StateFlow。展开平屏 dock 的 Settings 固定底部。
- WindowManager 监听实际铰链，处理 book、tabletop、实体遮挡及安全单区回退。页面、录音控制、导航、弹窗与工具提示避让；保持导航及阅读位置。展开平屏也按扣除导航后的可用宽度分栏：Models 语音 / 翻译，Settings 显示与隐私 / 性能与诊断，Sessions 列表 / 所选详情，Live 历史 / 最新译文和控制。收窄保留所选记录及详情阅读位置，紧凑详情的系统返回先回列表。详见 06_GUI。
- GUI 使用牛皮纸暖色主题：淡黄纯色背景、浅米黄不透明面板、棕色主按钮与深棕正文；背景及面板不再绘制曲线渐变和玻璃高光，字幕保持无框排版。支持浅/深/系统主题、降低透明度、8–24 sp 字号、系统字体缩放、手机及侧栏布局；深色模式使用暖棕色，降低透明度时移除面板轻微阴影。`GlassTheme.kt` 统一管理 4 / 8 / 12 / 16 dp 圆角与 16 dp 正文 / 8 dp 面板边距；按钮和分段控件显式使用主题圆角，避免 Material 默认胶囊形状。布局避让及交互逻辑沿用原实现。
- Android 麦克风前台服务采集 PCM16 / mono，优先 16 kHz，回退 48 / 44.1 / 32 / 8 kHz。IO 线程非阻塞读取，仅 `trySend` 到容量 32 的 DSP 队列，独立 worker 用 Media3 common 1.11.1 SonicAudioProcessor 转为 16 kHz，再非阻塞送到容量 32 的推理输入队列。电平及采样时钟最多 10 Hz 更新；停止首先取消采集并释放 AudioRecord，重采样尾帧排空不等待推理。暂停还冲刷不足 512 samples 的 VAD 尾窗，恢复保留采样偏移。通知有停止入口，不保存 PCM。
- 采样时钟由同一输入 epoch 累计样本换算，不逐帧取整。输入设备切换、原始队列缺帧结束旧 DSP 流，首个成功输出显式标记不连续，VAD 不把不同输入拼成一句。输入失效 `ERROR_DEAD_OBJECT` 30 秒内最多重开 3 次，保留恢复期间的样本缺口；权限撤销立即失败并释放。设置页显示实际 AudioRecord 输入格式、设备类型、恢复与丢帧计数。实际路由和麦克风兼容性待真机验证。
- Silero 将音频分段，SenseVoice `language=auto`；语段中每秒至多一个候选 partial，最终语段优先于 partial。partial 队列仅保留最新项，final / speaker / translation 队列分别为 4 / 4 / 8。捕获丢帧后建立新的绝对样本区域，不压缩缺口；积压和不可用译文有可见状态。
- Hy-MT2 1.8B STQ1_0 接入中 / 英 / 日 / 韩 / 粤五语双向选择，两个语言下拉框默认自动识别 → 中文，unknown 不猜测方向。ASR 保留 ko / yue 独立标签；新设置保存两框顺序，旧四种模式及历史字幕兼容。初次需要时创建翻译 context，空闲压力降级释放，恢复时重建；单语段清空 KV。CPU ASR 2 / VAD 1 / speaker 1 threads；翻译自动模式按核数增加预算，生成最多 4 / 预填充最多 6，省电或中度热压力最多 2 / 2（单核为 1 / 1）。预算在每句生成前更新并复用 context，已开始的调用完成后才降档；1024 context / 256 输出 tokens / 60 秒 CPU deadline。greedy 与上游随机采样建议不同，质量和手机性能待验收。
- 声纹独立抽取，语段短于 1.2 秒保留 unknown。在线质心余弦匹配、歧义拒绝，至少两次匹配证据后回填之前 unknown。最近 256 份 embedding 驱动周期性高门槛合并 / 拆分，有冷却期，退休标签不复用，会话累计分配上限 16 个。合并修订所有历史字幕，拆分只修订仍有证据的语段；原文 / 译文独立修订。初始阈值属于待校准参数，不是实测准确率。标签在会话内有效，不使用语种作为身份特征，详见 04_AUDIO_AND_SPEAKERS。
- 停止给积压任务最多 15 秒排空窗口，之后取消队列；已经进入的同步 JNI 调用仍须返回后才能释放资源，15 秒不是强制中断原生调用的保证。模型租用保持到全部 worker 退出和 native shutdown，期间禁止导入替换或删除；服务销毁也等待资源清理，防止新会话重用仍在关闭的运行库。新服务等待旧会话释放运行库后，会再次检查停止请求与当前系统压力，再开始加载模型。
- 演示模式依次产生 partial、final、speaker 和 translation 事件；独立 revision 防止旧结果覆盖新原文、跨会话混入及中文重复翻译。演示不需要麦克风。
- Room 保存可关闭的文本记录；支持查看、重命名、删除，SAF 导出 UTF-8 TXT / SRT / JSON。
- 生产会话结束也由 Service 保存，UI 重建或折叠不会成为数据持有者。设置页离线显示 SDK 随包许可、NOTICE 与 SOURCE。

折叠双栏使用稳定的 Compose 内容槽及现有 WindowManager 几何层，不按机型硬编码，不依赖系统强制拉伸 / 应用复制。实体分隔与 FULL 遮挡优先；无分隔时依据正文实际宽高与字体决定双栏。普通手机的模型 / 设置仍单列滚动，模型集合只有五个条目，使用普通 Column 避免无限高度中的嵌套 LazyColumn；记录保留有界 viewport 的 LazyColumn。实时伴随区独立展示最新语段，不强制改变左侧历史阅读位置，新语段将伴随区滚回顶部，同段异步修订不重置它。
- DataStore 保存显示、隐私和性能设置；控件只提交修改字段，连续更新不会用旧快照重新打开记录保留。关闭记录的意图在 ViewModel 提交时生效，保存门禁不等待这份写入；持久化设置读取遇到 IO 错误时拒绝保存，而不是采用显示层默认值。APK的INTERNET仅用于主动模型下载；无ACCESS_NETWORK_STATE、广泛存储权限或遥测SDK。
- 模型 ZIP 通过 SAF 导入私有目录，校验清单、文件大小与 SHA-256，限制文件数和解压体积，拒绝越界路径、重复及未登记文件。校验后替换；失败保留旧版本，进程重启可恢复备份。清单哈希验证文件完整性，不认证发布者身份或权重许可。

## 系统压力与性能模式

压力监测只在录音 Service 存活期间运行，不写入 PCM、文本或硬件统计日志。Android 10 / API 29 起监听 PowerManager 热状态；Android 9 保持内存轮询，不假装取得温度。每 5 秒采样系统可用内存、OS `lowMemory` 标记及 Java 堆余量，热状态回调可立即提升保护等级。设备不提供热状态时显示不可用；这些状态不是摄氏度。

| 状态 | 初始触发条件 | 行为 |
| --- | --- | --- |
| 正常 / 自动 | 未命中其他条件 | 临时字幕约 1 秒间隔，最终识别、翻译与声纹开启；翻译按核数使用最多 4 / 6 线程 |
| 正常 / 省电 | 用户选择省电 | 临时字幕约 2 秒间隔，声纹暂停，最终识别与翻译保留；翻译最多 2 / 2 线程 |
| 节流 | 热状态 MODERATE | 临时字幕约 3 秒间隔，声纹暂停，翻译保留并降至最多 2 / 2 线程 |
| 原文优先 | 热状态 SEVERE、OS lowMemory、系统可用内存低于 192 MiB 或 Java 堆余量低于 32 MiB | 不再安排临时识别、翻译及声纹，只保留最终原文 |
| 停止保护 | 热状态 CRITICAL 及以上、系统可用内存低于 96 MiB 或 Java 堆余量低于 16 MiB | 停止采集，取消尚未执行的推理，等待正在执行的 JNI 返回后清理 |

任一条件即可升级；压力降低需保持同一较低档位 30 秒才恢复，新任务按当前模式重新延迟加载。省电选择不能覆盖系统保护。中度 / 严重降级不从监测线程直接释放推理对象，避免与正在执行的调用竞态；已经开始且正常完成的请求可保留结果。旧语段缺失的翻译标记不可用，不自动重排或无限重试。临界压力取消后，尚未完成的临时字幕保持临时状态。

这些是待真机校准的初始参数，不证明能避免系统杀进程或 native OOM。系统可用内存并非当前进程的可用额度，Java 堆余量也不包含全部 native 分配；PSS / RSS 采样入口已实现，但尚无真实运行测量或长期热机报告。主机策略与生命周期测试不能证明实际模型的加载峰值、释放速度或重载性能。

## 运行诊断

生产会话取得模型租用后创建一次 RunDiagnostics，记录 ANDROID_MICROPHONE 来源。MODEL_INIT / MODEL_RELEASE、完整 VAD 窗口、DSP push、ASR、speaker 和翻译调用采用单调时钟；采集线程只累加已注册采样率的原子计数，不生成 JSON、排序或采样内存。每 5 秒独立 IO worker 采样进程 PSS / RSS / native heap、系统可用内存、Java 堆余量、热状态与电池。硬件值独立读取，不把未激活的压力监测默认 NORMAL 当作真实热状态。

设置诊断区的下载图标可选择 JSON / CSV，再通过 SAF 保存最近一次会话报告。报告只在内存中保留，不随 Room 字幕保存；重启进程后不恢复。选择 SAF 前固定目标，导出在 IO 执行。CSV 为 `path,type,value` 扁平记录，保留 null 类型并转义引号 / 换行、防止公式前缀；不是每行一个性能调用的表格。字段和采样限制见 07_QUALITY。它不记录语音或识别内容，也不自动判定准确率 / 设备验收通过。

来源与采样 API：[Debug.MemoryInfo](https://developer.android.com/reference/android/os/Debug.MemoryInfo)、[Debug](https://developer.android.com/reference/android/os/Debug)、[AOSP Debug RSS 实现](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/jni/android_os_Debug.cpp)。PSS 与 RSS 的 API 数值按 kB 转换为 bytes；低版本 VmRSS 也按 kB 解析。模型哈希复用已租用的清单，不额外重算普通文件哈希。

## 模型包协议

ZIP 根目录必须有 `manifest.json`。ID 仅允许 `sensevoice`、`silero-vad`、`speaker`、`hy-mt2`。以下是结构模板，必须用实际文件大小、SHA-256 和许可替换占位值，不能直接导入：

```json
{
  "id": "sensevoice",
  "version": "实际制品版本",
  "abi": "arm64-v8a",
  "sourceURL": "实际上游制品来源",
  "license": "实际权重许可",
  "files": [
    { "path": "model.onnx", "size": 0, "sha256": "实际文件的64位SHA256" },
    { "path": "tokens.txt", "size": 0, "sha256": "实际文件的64位SHA256" }
  ]
}
```

单文件不超过 512 MiB，总解压量不超过 1 GiB；清单不超过 64 KiB，清单文件最多 64 个，ZIP 条目最多 128 个。导入校验文件完整性，真正启动前检查必需文件；ONNX 图、声纹维度及翻译模型结构仍须实际 native 加载验证。

| ID | 必需布局 |
| --- | --- |
| sensevoice | 根目录 `model.onnx`（SenseVoiceSmall INT8）、`tokens.txt` |
| silero-vad | 根目录 `silero_vad.onnx`，16 kHz / window 512 的兼容模型 |
| speaker | 根目录 `speaker.onnx`，sherpa 支持的 16 kHz speaker embedding 模型 |
| hy-mt2 | 根目录 model.gguf，固定官方 STQ1_0 权重、SOURCE.json 与 LICENSE.txt，见 hymt-model.lock.json |

前三个模型是生产采集启动的必需条件。Hy-MT2 包缺失时原文仍可识别，但对应译文明确标不可用。取得并审核权重后，可用 `scripts/pack-model.ps1` 生成导入 ZIP；该脚本只读取源目录，不更改权重，按协议计算必要的模型 SHA-256，拒绝覆盖现有输出。许可及来源需显式提供，脚本不认证发布者或替代权重许可审查。

```powershell
./scripts/pack-model.ps1 -Id sensevoice -SourceDirectory <audited-model-directory> -Version <version> -License <weight-license> -SourceURL <upstream-artifact-url> -OutputFile <new-output.zip>
```

当前翻译权重通过 `scripts/prepare-hymt-model.ps1` 准备，ASR / VAD / CAM++ 通过 `scripts/prepare-speech-models.ps1` 准备；均在电脑下载校验并生成本地包，`-Offline` 仅用缓存并重新验证。默认 ASR 是三语 TTS auto 检查通过的官方 2024 原版；2025 粤语候选仅显式 opt-in，当前检查失败，不推荐作为生产导入基线。打包脚本保留根目录 LICENSE / NOTICE / COPYING / SOURCE 附带文件。当前四包主机导入需显式开启独立测试，普通回归不依赖缓存；完整命令、版本、许可与证据边界见 [14_MODEL_PREPARATION](14_MODEL_PREPARATION.md)。模型准备后推理仍不联网；用户主动下载权重见[ADR-0007](adr/0007-user-initiated-model-downloads.md)。

## 设备验证入口

`assembleDebugAndroidTest` 只构建测试 APK，不代表测试已运行。设备端 `NativeRuntimeSmokeTest` 检查固定版本JNI加载、模型下载INTERNET权限和无网络状态权限、Hy JNI加载及缺权重拒绝；执行时指定该类，避免误把需要权重的测试当成零配置 smoke。

```text
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.jd.rc.localtranslate.NativeRuntimeSmokeTest
```

`NativeModelTest` 需要先在应用导入已审核的三个语音包与 Hy-MT2 包，并将公开、许可明确的单声道英语测试 WAV 放在目标应用私有目录；传入 `audioFile` 参数。支持 8–96 kHz 输入，最大 32 MiB / 90 秒；建议分别使用 16 / 44.1 / 48 kHz 公开录音。该测试按输入时间非阻塞投递到生产 DSP 前端，再经过 VAD / ASR / 翻译管线，检查英语原文、中文译文、原始及推理输入无丢帧、无语段丢弃、资源释放。缺前提会失败，不会悄悄 skip 并声称真实推理通过。它仍不能替代实际麦克风、中文/日文、声纹跨语言、折叠屏和热机验收。

`scripts/verify-native.ps1` 检查打包 ARM64 库的 ELF PT_LOAD 与 APK 16 KB 对齐，不替代 16 KB 设备上的实际加载。

NativeModelTest 在管线清理后导出 `validation/diagnostics/native-model.json` 和 `.csv`，位于目标应用私有 files 目录，来源为 ANDROID_TEST_FIXTURE。预初始化失败尚不能产生完整管线报告；测试报告不携带 WAV、字幕或异常消息。每次覆盖本测试的两个固定报告，需在下一次运行前取回证据；当前设备测试仅编译，未实际生成真机数据。

## 下一步

1. 审核、准备并实际运行权重，取得中英日、翻译和 A-B-A / 跨语言声纹证据；校准阈值，完善历史标签合并/拆分与过载行为。
2. 验收 resampler 与路由变化，在真实多模型压力下校准热 / 低内存降级及资源释放；使用结构化报告取得 PSS / RTF / 延迟实测，并补充异常生命周期回归。
3. 真机验证权限拒绝、后台/锁屏、通知停止、音频占用、字体缩放、飞行模式、折叠姿态、16 KB page size 与 30–60 分钟热机性能。
4. 完成原生依赖及权重合规、项目许可证决策和可重现发行审核。不能以编译通过代替项目完成。

未指定项目 LICENSE，未将模型权重打入 APK 或 Git，公开发行仍为预发布测试版；当前四套模型包仅在本地忽略目录打包，Release不附权重。

## GUI 性能工具入口

独立性能测试模块的构建、API 31+ ARM64 真机运行及数据清除限制见 [17_BENCHMARK.md](17_BENCHMARK.md)。工具只用固定演示，不加载模型或打开麦克风，不能作为真实推理验收。

Hy-MT2 模型准备：./scripts/prepare-hymt-model.ps1（离线缓存用 -Offline）。NativeHyTranslationTest 另需通过 -e modelPackage 传入目标 app 私有 ZIP 路径；实际经生产导入器加载 STQ 权重，验证中英日六向公开问候并重建 context，不涉及麦克风或 ASR。旧 Mozilla 准备命令保留为历史工具，当前不再接入。

隔离 x86 模型验证构建：先 ./scripts/build-hymt-native.ps1 -Abi x86_64，再 ./scripts/build.ps1 -Tasks @(':app:assembleHyValidation', ':app:assembleHyValidationAndroidTest', '-PhyValidation=true')。测试应用 com.jd.rc.localtranslate.hyvalidation，不向普通包导入测试音频；NativeHyTranslationTest 的固定公开文本报告位于目标应用 files/validation/hy-public-fixtures.json。该配置不用于发布或手机性能结论。
