# 02 — 技术栈与版本策略

**Status: Native inference integration in progress; JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.4.10 and Compose BOM 2025.05.01. Validation is tracked separately in 11_VALIDATION.md.**

| 方面 | 选定方案 | 备注 |
| --- | --- | --- |
| OS | Android minSdk 28；compile/target API 36 作为建项基线 | Android 17 / API 37 独立回归 |
| ABI | arm64-v8a | 暂不保证 32 位和 x86 |
| 语言 | Kotlin 2.x + JDK 17 | Gradle Kotlin DSL |
| GUI | Jetpack Compose + Material 3 | Compose BOM 统一依赖，Navigation Compose |
| 状态 | ViewModel + StateFlow + Coroutine | 单向数据流，UiState immutable |
| DI | 构造函数手动注入首版 | 后续复杂度需要时才引入 Hilt |
| 音频 | AudioRecord / AudioManager / Foreground Service | 麦克风前台时机启动 |
| VAD/ASR/LID | sherpa-onnx + Silero + SenseVoiceSmall INT8 | ONNX Runtime + Android Kotlin/JNI |
| Speaker | sherpa speaker embedding + 本项目聚类 | 离线 segmentation 可选作为后台复核 |
| 翻译 | Hy-MT2 1.8B 1.25-bit / llama.cpp STQ JNI | 固定未合并 STQ PR；英日直接译中，见 ADR-0006 |
| 数据 | Room + DataStore | SAF 导入、私有目录管理模型 |
| 单测/质量 | JUnit、Compose UI Test、Android Instrumentation、Macrobenchmark | Android CI + 真机表 |

### 构建与锁版本规则

0.1.0 使用 Version Catalog / libs.versions.toml 和 Gradle Wrapper；实际构建结果记录在 11_VALIDATION.md。Compose BOM 保守固定为 2025.05.01。当前翻译接入 Hy-MT2 STQ，替代旧 Foxlet 0.5.0 AAR；sherpa-onnx 1.13.8 JNI 与 Kotlin API 固定到提交 `11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf`，JNI 由 NDK 29.0.14206865 / CMake 3.22.1 构建，关闭 TTS / 离线 diarization，保留 speaker embedding。开发依赖经 `scripts/bootstrap-native.ps1` 放入忽略目录 `.tools/native`。构建期联网不等于应用运行期联网；不调用 SDK 的下载或更新接口，APK 的 INTERNET 仅用于用户主动下载模型，推理不联网（ADR-0007）。禁止动态 latest / + 版本。原生分发边界见 ADR-0005。

Kotlin 2.4.10 是旧 Foxlet 接入阶段固定的版本，本次后端更换不同时升级或降级构建工具。Room 2.7.1 的 kapt classpath 显式使用同版本 `kotlin-metadata-jvm`，解决原处理器最多读取 2.2 元数据的问题；数据库 schema 未变。Gradle 8.11.1 目前仍受支持但有未来弃用提示，后续工具链回归须保留此提示而非宣称零警告。

音频回退重采样固定使用 AndroidX Media3 common 1.11.1 的 SonicAudioProcessor，默认 speed / pitch 均为 1；仅引入 common，不引入播放器或网络数据源。音频时钟与流边界由独立前端维护，长时实际识别质量仍需设备数据验证。来源：[固定版本重采样接口](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/SonicAudioProcessor.java)。

ABI、minSdk 与 CPU 指令回退以最终打包原生库为准；Android 16 KB page size 兼容列入专门验证项。暂不把 NNAPI/厂商 NPU 作为必需，CPU 为 fallback。Python 仅用于开发/离线评测，不在手机端运行。

**不采用：**Flutter、React Native、WebView 主 UI、云 API、手机端 PyTorch/Python。

独立 GUI 基准工具固定 AndroidX Benchmark 1.4.1、UIAutomator 2.3.0、Tracing Perfetto core / binary / handshake 1.0.1，API 31+、ARM64；不增加正式应用运行依赖。Perfetto 1.0.1 用于 16 KB 段对齐，实际 APK 另有静态门禁。运行边界见 17_BENCHMARK.md。

资料：[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Foxlet](https://github.com/yinvoke/foxlet-translate)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)。

Hy-MT2 权重与 STQ runtime 固定版本见 scripts/hymt-model.lock.json / scripts/build-hymt-native.ps1。不编译 llama common/server/网络客户端；自动模式 CPU 生成最多 4 / 预填充最多 6 threads，按核数限幅，省电或中度热压力最多 2 / 2，上下文 1024 tokens。预算公式和验收边界见 21_TRANSLATION_SPEED_PLAN.md；依赖 PR #22836 尚未合并，不能当作普通上游发行支持。
