# 02 — 技术栈与版本策略

**Status: Accepted technology choices; exact build tool versions pending first verified Android build.**

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
| 翻译 | Foxlet Translate 0.5.0 AAR | Mozilla MT、Marian/Bergamot |
| 数据 | Room + DataStore | SAF 导入、私有目录管理模型 |
| 单测/质量 | JUnit、Compose UI Test、Android Instrumentation、Macrobenchmark | Android CI + 真机表 |

### 构建与锁版本规则

建议使用 Version Catalog / libs.versions.toml、Gradle Wrapper、固定 SHA/Release tag 的原生依赖。Compose 官方 BOM 文档现示例 2026.08.00，Foxlet 当前核实 v0.5.0。**Kotlin、AGP、Gradle、sherpa-onnx 的具体组合必须在首个 Gradle 工程使用 Android Studio 实际编译后锁定**，避免文档宣称一个未验证的兼容矩阵；禁止动态 latest / + 版本。

ABI、minSdk 与 CPU 指令回退以最终打包原生库为准；Android 16 KB page size 兼容列入专门验证项。暂不把 NNAPI/厂商 NPU 作为必需，CPU 为 fallback。Python 仅用于开发/离线评测，不在手机端运行。

**不采用：**Flutter、React Native、WebView 主 UI、云 API、手机端 PyTorch/Python。

资料：[Compose BOM](https://developer.android.com/develop/ui/compose/bom)、[Foxlet](https://github.com/yinvoke/foxlet-translate)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)。
