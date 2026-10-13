# RC-LocalLiveTranslate

**Android 本地同传 · 中英日自动识别 · 会话内说话人追踪 · 双向翻译字幕**

[下载最新版 0.1.0-prealpha.4](https://github.com/JDui/RC-LocalLiveTranslate/releases/tag/v0.1.0-prealpha.4) · Android 9+ / ARM64 · APK约75.5 MiB，**不含模型**。

识别和翻译全部在手机本地运行，不上传录音、字幕或诊断。首次使用可在「离线模型」页**一键下载缺失模型**，也可通过本地ZIP离线导入；模型准备后可断网使用。仅主动下载权重时访问网络，无账号、遥测或云推理。当前仍为预发布测试版，真实ARM手机音频链路、准确率及长期性能尚未验收。

## 实际界面

下图均来自本次签名Release APK的Android模拟器，系统字体100%，保留完整画面、原始像素，无裁切或图像缩放。竖屏1080×2520（21:9、480dpi）；接近方形的展开屏1800×1920（320dpi）。展开尺寸展示侧栏/双栏适配，不代表真实铰链或OEM开合测试。字幕为明确标记的固定演示，不是实际识别结果。点击图片可查看原图。

| 21:9 1080P竖屏 | 接近方形的折叠展开屏 |
| --- | --- |
| ![竖屏字幕](docs/assets/screenshots/phone-live.png) | ![展开字幕](docs/assets/screenshots/fold-live.png) |
| ![竖屏模型](docs/assets/screenshots/phone-models.png) | ![展开模型双栏](docs/assets/screenshots/fold-models.png) |
| ![竖屏记录](docs/assets/screenshots/phone-records.png) | ![展开记录双栏](docs/assets/screenshots/fold-records.png) |
| ![竖屏设置](docs/assets/screenshots/phone-settings.png) | ![展开设置双栏](docs/assets/screenshots/fold-settings.png) |

## 安装和模型准备

1. 下载Release中的 `RC-LocalLiveTranslate-0.1.0-prealpha.4-arm64.apk` 并安装；沿用已有正式签名，可覆盖以前的公开版本。
2. 打开「离线模型」，点击「一键下载缺失模型」。完整原始权重约697 MiB，至少预留2 GiB空间；有进度与取消，失败可重试，仅下载缺项、保留已安装包。网络连接取决于GitHub/Hugging Face可达性；APP没有静默下载或后台续传，退出进程后需重试。
3. 也可在电脑用 `scripts/prepare-speech-models.ps1` 和 `scripts/prepare-hymt-model.ps1` 生成离线ZIP，经SAF导入。所有路径、固定大小、SHA、版本和许可证均在安装前校验。
4. 四模型就绪后，选择译中文或中日/中英/英日双向，授权麦克风，从可见字幕页开始。演示开关位于设置，可在无模型时查看固定示例。
5. 记录可关闭、回看、重命名、删除，并导出TXT/SRT/JSON；不保存原始音频。

首次启动不含权重，一键下载入口如下；其他截图的4/4是下载后的安装状态：

<img src="docs/assets/screenshots/phone-model-download.png" alt="首次启动一键下载缺失模型" width="320">

| 本地模型 | 用途 | 权重许可 |
| --- | --- | --- |
| SenseVoice Small INT8（2024） | 中英日ASR与语言检测 | FunASR Model License 1.1 |
| Silero VAD | 语音活动检测 | MIT |
| CAM++ | 会话内说话人向量 | Apache-2.0 |
| 腾讯Hy-MT2 1.8B STQ1_0 | 中英日翻译 | Apache-2.0 |

来源、版本、固定摘要和许可全文随下载清单保留；权重许可不等同于推理引擎或本项目许可证。旧Foxlet/M2M100包不用于当前翻译。

## 当前实现和边界

Kotlin + Compose + Material 3；AudioRecord采集与推理/UI隔离；sherpa-onnx 1.13.8执行本地VAD/ASR/LID/embedding，Hy-MT2由固定llama.cpp STQ CPU/JNI执行。支持紧凑字幕、8–24 sp字号、浅深主题、记录时间/城市命名、展开双栏和铰链安全区、有界字幕窗口和分段数据库存储。

本轮183项主机测试通过，最终签名Release的JNI/权限smoke 2项通过，模型下载/校验安装及两种尺寸画面实际检查完成。**没有真实ARM手机同传、CER/WER、译文质量、跨语种同人身份、长时间热机/电池或性能结论。**Speaker A/B是会话内聚类标签，不是身份识别；SenseVoice按语段推理，译文有延迟。

STQ内核来自固定未合并PR，属于实验依赖。项目自身LICENSE尚未选定；原生、模型和传递依赖条款分别保留，不宣称完整商用合规。

## 构建和文档

```powershell
./scripts/build.ps1 -Tasks @('assembleRelease', 'testDebugUnitTest', 'lintRelease', 'assembleDebugAndroidTest', '--no-daemon', '--max-workers=2')
./scripts/package-prealpha.ps1 -Version 0.1.0-prealpha.4
```

普通构建不内置权重。模型、签名材料和本地APK均放在忽略目录，不提交Git。

- [最新版发行说明](docs/24_PREALPHA_4.md) · [验证证据和待测项](docs/11_VALIDATION.md)
- [开发准备和包协议](docs/10_DEVELOPMENT.md) · [模型来源与准备](docs/14_MODEL_PREPARATION.md)
- [隐私/权限/许可矩阵](docs/09_COMPLIANCE.md) · [用户主动下载ADR](docs/adr/0007-user-initiated-model-downloads.md)
- [GUI规格](docs/06_GUI.md) · [折叠适配验收](docs/13_ADAPTIVE_ACCEPTANCE.md) · [真机基准工具](docs/17_BENCHMARK.md)
- [有界字幕与分段存储](docs/22_EFFICIENCY_MAINTENANCE.md) · [翻译速度规划](docs/21_TRANSLATION_SPEED_PLAN.md)
- [产品范围](docs/01_REQUIREMENTS.md) · [技术栈](docs/02_STACK.md) · [架构](docs/03_ARCHITECTURE.md) · [音频与说话人](docs/04_AUDIO_AND_SPEAKERS.md) · [翻译路由](docs/05_MODELS_AND_TRANSLATION.md)
- [质量门禁](docs/07_QUALITY.md) · [路线](docs/08_ROADMAP.md) · [图标](docs/12_ICON.md) · [可选本地整合构建](docs/19_BUNDLED_LOCAL.md) · [贡献规则](AGENTS.md)

上游：[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) · [SenseVoice](https://github.com/FunAudioLLM/SenseVoice) · [Hy-MT2](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF) · [Compose](https://developer.android.com/develop/ui/compose)。
