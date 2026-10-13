# 05 — 模型选择、翻译路由、离线管理

## 固定的翻译路径

- zh -> zh：原文直接显示，无翻译计算。
- en -> zh：Hy-MT2 1.8B 1.25-bit 直接译简体中文。
- ja -> zh：同一 Hy-MT2 模型直接译简体中文。
- unknown：延迟路由，必要时只显示原文并允许修正。

用户指定采用官方 Hy-MT2-1.8B-1.25Bit-GGUF，固定 STQ1_0 内核和权重身份。运行库及限制见 ADR-0006；不继续 M2M100 / Qwen 选型。旧 Foxlet 导入包仅保留为历史材料，不参与当前翻译。

## 初始模型候选

| 模块 | 候选制品 | 备注 |
| --- | --- | --- |
| ASR + LID | sherpa-onnx SenseVoiceSmall 原版 INT8 2024-07-17 | 本地三语 TTS auto 标签通过；2025 粤语微调候选全部返回 yue，未接受为基线 |
| VAD | sherpa-onnx Silero VAD asset 271935959 | 版本 / 发布者摘要已锁定，主机真实静音 / TTS 检查通过 |
| Speaker | 3D-Speaker CAM++ 中文 common ONNX asset 198893103 | 主机加载和 192 维向量通过；跨语言同人稳定性未验收 |
| Speaker segmentation | sherpa-onnx / pyannote 兼容制品 | 可选后台增强；资源开销未知 |
| MT zh / en / ja 双向 | 腾讯 Hy-MT2 1.8B 1.25-bit STQ1_0 | 权重 461,860,800 bytes，Apache-2.0；Android 实测结果另记 |

静态模型体积不是峰值 PSS。旧五套评测包的 URL / 版本 / 摘要依据与权重条款已登记，真实主机导入通过；ASR 选型依据见 [ADR-0003](adr/0003-multilingual-model-baseline.md)，详情见 [模型准备](14_MODEL_PREPARATION.md)。CAM++ 摘要为官方 HTTPS 制品的本次观察值，不是发布者提供值；全部模型的 Android 推理、真人质量及完整再分发合规尚未验收。

## 资源管理与离线保证

- 发行 APK 不含权重，模型页可主动一键下载缺失模型；INTERNET仅用于HTTPS权重下载。模型亦可经Storage Access Framework从本地文件导入，准备后可完全断网推理，见ADR-0007。
- 自定义 model manifest 记录 id、kind、version、sourceURL、sha256、size、license、requiredFiles、abi、language、quantization、benchmark。
- 解包到私有临时目录，逐项 SHA-256 校验后原子切换；错误或空间不足可恢复旧模型。
- 模型界面支持缺失/安装中/校验失败/已就绪/加载中/占用中/可删除状态；不能在正在推理时删除所需文件。
- 单个 Hy-MT2 context 按需加载，三语共用；空闲压力降级释放，恢复时重建，语段间清空 KV。
- JNI、ONNX Runtime 与 llama.cpp STQ 的 ABI/16 KB page size/so 符号冲突需要 Android 真机验证。

## 更新规则

禁止自动拉取未知模型。若后续加入模型下载器，要独立控制和展示网络权限；核心实时模式仍不得调用网络。

来源：[Foxlet Translate](https://github.com/yinvoke/foxlet-translate)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、[SenseVoice](https://github.com/QwenAudio/SenseVoice)。

当前模型包准备：`./scripts/prepare-hymt-model.ps1`，已有缓存可加 `-Offline`。仅允许固定版本 `tencent-hymt2-1.8b-stq1-9df5c824`，单文件 512 MiB / 总量 1 GiB 限制不变。

## 双向语言选择

实时页可选择中日、中英、英日。按 SenseVoice 的语段语言标签，将所选两种语言分别译成另一种；第三种语言只显示原文，未知标签不猜测翻译方向。仍保留默认“译中文”模式兼容旧设置。选择在新会话开始时固定，录音、暂停和启动/停止期间不可切换。说话人追踪不依赖语言选择。

同一份 Hy-MT2 权重支持六个方向，不增加模型包。JNI 每次接收明确的 zh/en/ja 目标语言，重置 KV，再构造目标语言提示。字幕与 JSON 记录保存 targetLanguage；旧记录按原先译中文规则读取。演示字幕按选择显示预置文本，仍标记为演示，不能作为模型准确率证据。

## 快速方案评测规划

现阶段仅考虑优化现有 Hy-MT2 STQ；模型替换暂缓，不执行新后端接入。线程对照、预加载、前缀缓存、逐步显示和队列等待的分阶段规划与验收见 [翻译提速规划](21_TRANSLATION_SPEED_PLAN.md)。
