# 05 — 模型选择、翻译路由、离线管理

## 固定的翻译路径

- zh -> zh：原文直接显示，无翻译计算。
- en -> zh：Foxlet 英译简体中文模型。
- ja -> en -> zh：Foxlet 日译英与英译中 pivot 双模型。
- unknown：延迟路由，必要时只显示原文并允许修正。

选择 Foxlet v0.5.0 AAR 是因为 Android ARM64、Kotlin 接口、离线 Bergamot/Marian 推理和多语向模型管理。**日语经英语中转存在人名、敬语、语义损失**，下一阶段比较真正日中直译模型；未经 A/B 测试不武断改动。

## 初始模型候选

| 模块 | 候选制品 | 备注 |
| --- | --- | --- |
| ASR + LID | sherpa-onnx SenseVoiceSmall 原版 INT8 2024-07-17 | 本地三语 TTS auto 标签通过；2025 粤语微调候选全部返回 yue，未接受为基线 |
| VAD | sherpa-onnx Silero VAD asset 271935959 | 版本 / 发布者摘要已锁定，主机真实静音 / TTS 检查通过 |
| Speaker | 3D-Speaker CAM++ 中文 common ONNX asset 198893103 | 主机加载和 192 维向量通过；跨语言同人稳定性未验收 |
| Speaker segmentation | sherpa-onnx / pyannote 兼容制品 | 可选后台增强；资源开销未知 |
| MT en→zh | Mozilla 2.2 / Foxlet v0.5.0 固定目录 | 已下载校验并打包，原始权重 / 词表 51,934,991 bytes |
| MT ja→en | Mozilla 2.1 / Foxlet v0.5.0 固定目录 | 已下载校验并打包，原始权重 / 词表 54,769,181 bytes |

静态模型体积不是峰值 PSS。五套评测包的 URL / 版本 / 摘要依据与权重条款已登记，真实主机导入通过；ASR 选型依据见 [ADR-0003](adr/0003-multilingual-model-baseline.md)，详情见 [模型准备](14_MODEL_PREPARATION.md)。CAM++ 摘要为官方 HTTPS 制品的本次观察值，不是发布者提供值；全部模型的 Android 推理、真人质量及完整再分发合规尚未验收。

## 资源管理与离线保证

- 发行 APK 可以不请求 INTERNET 权限；模型经 Storage Access Framework 从用户本地文件导入，允许完全断网部署。
- 自定义 model manifest 记录 id、kind、version、sourceURL、sha256、size、license、requiredFiles、abi、language、quantization、benchmark。
- 解包到私有临时目录，逐项 SHA-256 校验后原子切换；错误或空间不足可恢复旧模型。
- 模型界面支持缺失/安装中/校验失败/已就绪/加载中/占用中/可删除状态；不能在正在推理时删除所需文件。
- 按需装载日→英与英→中模型，低内存时主动释放；评估换语言加载延迟。
- JNI、ONNX Runtime 与 Foxlet AAR 的 ABI/16 KB page size/so 符号冲突需要 Android 真机验证。

## 更新规则

禁止自动拉取未知模型。若后续加入模型下载器，要独立控制和展示网络权限；核心实时模式仍不得调用网络。

来源：[Foxlet Translate](https://github.com/yinvoke/foxlet-translate)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、[SenseVoice](https://github.com/QwenAudio/SenseVoice)。
