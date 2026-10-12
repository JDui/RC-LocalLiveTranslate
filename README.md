# RC-LocalLiveTranslate

**Android 原生 · 完全离线 · 中英日自动识别 · 说话人分离 · 双向翻译字幕**

> 项目阶段：[Android 0.1.0-prealpha.3](https://github.com/JDui/RC-LocalLiveTranslate/releases/tag/v0.1.0-prealpha.3)。本地 ASR / LID、说话人追踪与 Hy-MT2-1.8B 1.25-bit 翻译，支持中日、中英、英日双向选择。154 项主机测试及模拟器六向公开短句翻译通过；完整语音同传与手机性能尚未验收。升级说明见 [第三版说明](docs/18_PREALPHA_3.md)。

本版不再使用 Foxlet / M2M100。旧翻译模型不再使用，需要另行离线导入 Hy-MT2 模型包；权重不包含在 APK 内。

## 定位

模型进度：五套基线导入包已准备，实际文件导入 / 租约保护通过；Windows x64 的三语本地 TTS 检查通过。2025 粤语微调候选因自动语言标签回归未接受，改用官方 2024 原版作为待实机验收基线。主机结果不等于 Android 准确率或项目完成。

一个运行于 Android ARM64 手机上的实时本地翻译字幕工具：手机麦克风持续监听，识别中文/英语/日语，按照声纹分配 Speaker A/B/C 等会话内说话人标签，保留原文，可选中日、中英、英日双向翻译；按识别出的语言译成另一种。默认保留译中文模式。无需联网、后端服务或 TTS。

**注意：**说话人分离（diarization）不等于真实身份识别；允许短句暂标「未知」并在后续证据足够时修正。SenseVoice 以语段推理为主，实时字幕采用增量/模拟流式方案，不承诺零延迟。

## 技术选型

| 范围 | 技术 |
| --- | --- |
| Android | Kotlin、minSdk 28、ARM64、JDK 17 |
| GUI | Jetpack Compose、Material 3、Navigation Compose、MVVM / StateFlow |
| 音频 | AudioRecord、Media3 本地重采样、Silero VAD、16 kHz 推理 PCM、mic Foreground Service |
| ASR + LID | sherpa-onnx、SenseVoiceSmall INT8 |
| 说话人 | sherpa speaker embedding + 自研在线增量聚类/回填 |
| 当前开发翻译 | 腾讯 Hy-MT2 1.8B 1.25-bit、llama.cpp STQ，英→中 / 日→中 |
| 数据及模型 | Room、DataStore、SAF 文件导入、SHA-256 校验 |
| 构建与测试 | Gradle Kotlin DSL、Version Catalog、JUnit、Compose Test、真机基准 |

运行时完全离线：正式离线发行可不申领 INTERNET 权限，允许通过 SAF 从电脑/本地文件导入模型。CPU 为兼容基线，硬件 NPU 作为后续可选优化。

## 文档索引

- [紧凑字幕布局、记录命名与翻译提速规划](docs/21_TRANSLATION_SPEED_PLAN.md)

- [prealpha 安装与已知限制](docs/15_PREALPHA.md)
- [prealpha.2 恢复修复与升级](docs/16_PREALPHA_2.md)
- [prealpha.3 Hy-MT2 与双向翻译](docs/18_PREALPHA_3.md)
- [GUI 真机性能测试工具与隔离](docs/17_BENCHMARK.md)
- [Android 构建、已实现范围与模型包协议](docs/10_DEVELOPMENT.md)
- [当前验证结果与待测项](docs/11_VALIDATION.md)
- [翻译魔芋图标与生成记录](docs/12_ICON.md)
- [折叠适配质量与验收矩阵](docs/13_ADAPTIVE_ACCEPTANCE.md)
- [真实模型准备、固定来源与验证边界](docs/14_MODEL_PREPARATION.md)

- [产品范围与验收](docs/01_REQUIREMENTS.md)
- [技术栈与版本策略](docs/02_STACK.md)
- [模块架构与事件协议](docs/03_ARCHITECTURE.md)
- [录音、ASR 与在线声纹分离](docs/04_AUDIO_AND_SPEAKERS.md)
- [模型与翻译路由](docs/05_MODELS_AND_TRANSLATION.md)
- [GUI 与交互规范](docs/06_GUI.md)
- [测试与性能基准](docs/07_QUALITY.md)
- [开发路线](docs/08_ROADMAP.md)
- [开源许可证、隐私与权限](docs/09_COMPLIANCE.md)
- [架构决策 ADR-0001](docs/adr/0001-native-android.md)
- [首版模块边界 ADR-0002](docs/adr/0002-initial-implementation.md)
- [多语模型基线与选型证据 ADR-0003](docs/adr/0003-multilingual-model-baseline.md)
- [有界说话人历史修订 ADR-0004](docs/adr/0004-bounded-speaker-reconciliation.md)
- [无 TTS 原生分发 ADR-0005](docs/adr/0005-no-tts-native-distribution.md)
- [开发代理约束](AGENTS.md)

## 当前风险

1. SenseVoice 是按段识别，临时字幕需要反复截段推理；模型短句 LID 和标点效果需测试。
2. sherpa-onnx 现成 diarization 示例是离线处理，长期流式 SpeakerTracker 需要自研。
3. Hy-MT2 极低位量化的英日直译质量、延迟和内存尚待验收；STQ 内核依赖未合并 PR。
4. 多模型常驻的内存、热降频和连续 30 分钟稳定性尚未测量。
5. 各模型权重许可独立于推理引擎许可证，公开分发前要逐个核查。

## 项目源头

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) · [SenseVoice](https://github.com/QwenAudio/SenseVoice) · [Foxlet](https://github.com/yinvoke/foxlet-translate) · [Android Compose](https://developer.android.com/develop/ui/compose)
