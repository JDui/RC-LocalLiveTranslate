# RC-LocalLiveTranslate

**Android 原生 · 完全离线 · 中英日自动识别 · 说话人分离 · 中文实时字幕**

> 项目阶段：技术架构及文档初始化（2026-10-09）。**尚未实现 Android 工程、推理管线或可安装 APK；性能指标尚未经过实机验证。**

## 定位

一个运行于 Android ARM64 手机上的实时本地翻译字幕工具：手机麦克风持续监听，识别中文/英语/日语，按照声纹分配 Speaker A/B/C 等会话内说话人标签，保留原文并将英语/日语翻成简体中文。中文原话默认不重复翻译。无需联网、后端服务或 TTS。

**注意：**说话人分离（diarization）不等于真实身份识别；允许短句暂标「未知」并在后续证据足够时修正。SenseVoice 以语段推理为主，实时字幕采用增量/模拟流式方案，不承诺零延迟。

## 技术选型

| 范围 | 技术 |
| --- | --- |
| Android | Kotlin、minSdk 28、ARM64、JDK 17 |
| GUI | Jetpack Compose、Material 3、Navigation Compose、MVVM / StateFlow |
| 音频 | AudioRecord、Silero VAD、16 kHz PCM、mic Foreground Service |
| ASR + LID | sherpa-onnx、SenseVoiceSmall INT8 |
| 说话人 | sherpa speaker embedding + 自研在线增量聚类/回填 |
| 翻译 | Foxlet Translate v0.5.0、Bergamot/Marian，en→zh、ja→en→zh |
| 数据及模型 | Room、DataStore、SAF 文件导入、SHA-256 校验 |
| 构建与测试 | Gradle Kotlin DSL、Version Catalog、JUnit、Compose Test、真机基准 |

运行时完全离线：正式离线发行可不申领 INTERNET 权限，允许通过 SAF 从电脑/本地文件导入模型。CPU 为兼容基线，硬件 NPU 作为后续可选优化。

## 文档索引

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
- [开发代理约束](AGENTS.md)

## 当前风险

1. SenseVoice 是按段识别，临时字幕需要反复截段推理；模型短句 LID 和标点效果需测试。
2. sherpa-onnx 现成 diarization 示例是离线处理，长期流式 SpeakerTracker 需要自研。
3. 日语经英语中转翻译存在语义损失。
4. 多模型常驻的内存、热降频和连续 30 分钟稳定性尚未测量。
5. 各模型权重许可独立于推理引擎许可证，公开分发前要逐个核查。

## 项目源头

[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) · [SenseVoice](https://github.com/QwenAudio/SenseVoice) · [Foxlet](https://github.com/yinvoke/foxlet-translate) · [Android Compose](https://developer.android.com/develop/ui/compose)
