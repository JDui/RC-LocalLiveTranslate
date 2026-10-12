# 14 — 真实模型准备与证据边界

## 已准备的翻译模型（2026-10-09）

| 本地包 | 上游版本 | 权重与词表字节数 | 本次 ZIP 字节数 | 词表 |
| --- | --- | --- | --- | --- |
| en-zh-mozilla-2.2.zip | Mozilla 2.2 | 51,934,991 | 37,157,512 | src / trg 分离 |
| ja-en-mozilla-2.1.zip | Mozilla 2.1 | 54,769,181 | 37,315,912 | 共享 |

7 个二进制文件来自 Foxlet **v0.5.0** 的固定 [models.tsv](https://github.com/yinvoke/foxlet-translate/blob/v0.5.0/foxlet/src/main/resources/io/github/yinvoker/foxlet/models.tsv)，不跟随 main 或最新 Remote Settings。文件的原始名称、官方 Mozilla CDN URL、大小及上游 SHA-256 固定在 `scripts/translation-models.lock.json`。下载后逐项校验；缓存再次使用时也重新校验，不能只凭文件存在或包名判断有效。

Mozilla 维护者明确说明模型权重使用 [MPL-2.0](https://github.com/mozilla/translations/issues/1434#issuecomment-4734030816)。每包保留固定提交的 [许可原文](https://github.com/mozilla/firefox-translations-models/blob/e7957fc407441a5e3e35bbcbf9d60d9b35764618/LICENSE) 和 `SOURCE.json`，记录模型版本、语向、权重 URL / 摘要、目录出处、许可证据及未修改声明。权重及词表没有转换或改写。包内清单还校验这两份附带文件。此处只锁定这两套权重的来源与已声明许可，不构成整个 APK、训练数据或项目源码的完整发行合规结论；公开再分发前仍需检查适用源码取得方式和其他依赖。

准备结果位于 Git 忽略的 `.tools/models/mozilla/packages/`，不进入 APK、Git 或公开 Release。它们可由 Android 模型页通过本地 SAF 导入；应用本身没有下载器、INTERNET 或 ACCESS_NETWORK_STATE 权限。仅电脑上的显式准备命令需要联网。

## 复现

在项目根目录执行：

```powershell
./scripts/prepare-translation-models.ps1
./scripts/prepare-speech-models.ps1
./scripts/prepare-translation-models.ps1 -Offline
./scripts/prepare-speech-models.ps1 -Offline
./scripts/test-model-packaging.ps1
./scripts/build.ps1 -Tasks @('testDebugUnitTest', '--tests', 'com.jd.rc.localtranslate.PreparedModelImportTest', '-PverifyPreparedModels=true', '--no-daemon', '--max-workers=2')
```

第一次翻译准备最多下载 106,704,172 bytes 权重 / 词表及两份许可文件；语音准备另下载下文固定制品。`-Offline` 禁止缺文件时下载，并重验已有包。大小或摘要不符、包内容损坏时明确失败，不替换已有缓存或包；新包仅在完整校验后改名为最终路径。ZIP 时间戳与生成元数据不承诺字节级可重现，锁定的是实际权重、词表和许可原文。

真实导入测试默认不参加普通回归，必须显式传入 `-PverifyPreparedModels=true`；缺包是失败，不是跳过。原翻译 case 用约 107 MB 文件，调用生产 `ModelRepository.import` 解压 / SHA-256 / 原子安装，核对元数据和附带许可，再调用生产 `resolveTranslationModel`，确认英中分离词表、日英共享词表及 native 哈希集合。当前第二个 case 还安装全部五包，验证必需三语音模型的真实文件、租约保护、占用时拒绝替换 / 删除、重复关闭租约及释放后删除。五包共 375,178,899 bytes 权重 / 词表，另有许可和来源。测试运行于 API 35 Robolectric，不调用 ARM64 JNI，也不测翻译结果。

## 翻译阶段证据

- 在线准备通过：7 个文件与上游大小 / SHA-256 一致，两包内全部资产摘要通过。
- 本地 `-Offline` 重验通过，没有补下载。
- 真实导入测试：1 test / 0 failures / 0 errors / 0 skipped，执行 4.803 秒，Gradle BUILD SUCCESSFUL in 37s。这是主机测试耗时，不是设备导入性能承诺。
- 打包脚本：11 项通过，包括共享 / 分离词表、拒绝覆盖、ZIP 重复项、越界路径、未声明文件、缺来源、上游摘要不符、损坏摘要和错误大小。测试文件是合成夹具，不是额外模型。
- 实际导入测试 XML 副本保留在 `.tools/models/mozilla/validation/`，不随源码提交；普通回归不会把缺权重测试记为成功。
- 普通无权重回归复跑：128 tests / 0 failures / 0 errors / 0 skipped，BUILD SUCCESSFUL in 49s；真实模型类已排除，不是跳过。

## 语音基线与选型回归

| 本地包 | 版本身份 | 权重 / 词表字节数 | 本次 ZIP 字节数 | 权重声明 |
| --- | --- | --- | --- | --- |
| sensevoice-sherpa-int8-2024-07-17.zip | 官方原版 INT8，asset 288366523 | 239,549,735 | 155,906,169 | FunASR Model License 1.1 |
| silero-vad-sherpa-asset-271935959.zip | 官方兼容资产 ID，不推测 Silero semantic version | 643,854 | 522,742 | MIT |
| speaker-campplus-asset-198893103.zip | 3D-Speaker CAM++ 中文 common ONNX | 28,281,138 | 25,772,904 | 上游模型卡 Apache-2.0 |

语音清单固定在 `scripts/speech-models.lock.json`。ASR 压缩包及 VAD 有 GitHub 发布者摘要；解压 ASR 文件的摘要由该已校验压缩包登记。CAM++ 官方资产没有发布者摘要，清单明确写为 2026-10-09 从官方 HTTPS 制品观察到的 SHA-256，不能叫发布者提供的摘要。所有包保留固定提交的许可原文、作者 / 来源 / 摘要依据和 `SOURCE.json`。仅按运行要求将模型文件重命名，不改权重字节；不导入上游压缩包里的未知许可 WAV。

最初准备的 **2025-09-09 粤语微调版未被接受为三语自动识别基线**。固定三段本地 TTS、`language=auto`、ITN=true、16 kHz、2 threads：2025 的英语 / 普通话 / 日语都返回 `<|yue|>`；日语文本也明显偏离原句。用同样样本及断言比较官方 2024 原版，分别得到 `<|en|>` / `<|zh|>` / `<|ja|>` 和非空、人工查看可对应原句的文本。因此默认准备流程选择 2024；没有改成手动语种、放宽断言或将错误标签映射成目标语言。依据见 [ADR-0003](adr/0003-multilingual-model-baseline.md)。被拒候选仍以 `asrAlternatives` 登记，旧包移至 `.tools/models/speech/rejected-packages/`，与默认三语音包目录隔离；失败报告保留，仅可显式 `-Use2025Candidate` 准备 / `--use-2025-candidate` 检查，不推荐导入生产会话。

### 主机真实模型检查

在隔离 `.tools/sherpa-host` 安装 `sherpa-onnx==1.13.8`（含同版本 core）及 `numpy==2.2.6`。当前使用本机 Python 3.12.14。这不是替换 Android AAR / JNI，也不随 APK 提供 Python：

```powershell
<python-3.12> -m pip install --only-binary=:all: --target .tools/sherpa-host sherpa-onnx==1.13.8 numpy==2.2.6
<python-3.12> ./scripts/verify-speech-host.py
./scripts/prepare-speech-fixtures.ps1
<python-3.12> ./scripts/verify-speech-host.py --spoken-fixtures
```

夹具脚本需 PowerShell 7 和本机已安装的 Zira Desktop、Huihui Desktop、Haruka Desktop。三段测试文字由本项目自写，使用 [System.Speech 输出 WAV](https://learn.microsoft.com/en-us/dotnet/api/system.speech.synthesis.speechsynthesizer.setoutputtowavefile) 和已安装离线语音生成；不播放、不录麦克风、不从未知来源下载录音。WAV / manifest 仅放忽略目录，语音软件仍受其 Windows 条款约束，不将音频再分发。生成脚本拒绝覆盖已有夹具，后续直接重跑检查即可。

最终 2024 基线基础检查 6 项：实际 native 加载、两轮 2 秒静音 VAD、ASR 静音返回结构、CAM++ 双频音的 192 维有限非零向量；spoken 模式再加 3 项三语 TTS 标签 / 非空文本与独立 VAD 检测，共 **9 项通过**。三语每种只有一个合成样本，不是代表性 CER / WER、真人 LID 或声纹准确率；ASR 是整文件推理，VAD 独立检查，不是 Android 5 秒语段管线。Python 作用域结束和再次创建不证明 Android 资源释放。报告耗时只含 Windows x64 操作，不能用作手机速度承诺。

报告位于 `.tools/models/speech/validation/host-smoke.json` / `host-tts-smoke.json`，来源严格标为 `HOST_WINDOWS_X64_SYNTHETIC`；其中 reference / hypothesis 是自写夹具文字，不是私密会话。新尝试先写 `passed=false, state=incomplete`，防止加载失败留下旧绿色报告。2025 负例报告 `host-tts-smoke-2025.json` 为 passed=false，CLI 非零退出；不能与成功项目合计。首次主机脚本误用不存在的 `get_result_as_json` 失败，按固定版实际 `stream.result` 接口修正后才执行通过。

最终五包真实导入：**2 tests / 0 failures / 0 errors / 0 skipped**，Gradle BUILD SUCCESSFUL in 36s，测试执行 11.551 秒；这是主机导入检查，不是设备导入性能承诺。XML 在 `.tools/models/speech/validation/`。打包 / 下载安全脚本现 **17 项通过**，新增损坏缓存、离线缺文件、路径越界、非白名单来源及非 HTTPS 拒绝。普通回归仍默认不加载权重。

最终普通回归 BUILD SUCCESSFUL in 50s，128 tests / 0 failures / 0 errors / 0 skipped。真实模型测试类默认排除，未把被拒候选的失败或缺包 skip 算作通过。

## 未完成

五套基线包已准备并可通过主机生产导入器；尚无它们在 Android 上的实际 JNI / Foxlet 加载、真人中英日与短句识别、英文直译 / 日语 pivot、声纹 A-B-A / 同人跨语言、释放 / 重载、飞行模式或内存 / 热机证据。FunASR 底座为独立自定义权重条款，不是 sherpa 的 Apache-2.0；全部公开再分发合规与项目源码许可仍未完成。ADB 当前没有设备，不能将本地 TTS 或主机推理计为 Android 里程碑通过。
