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
| ASR + LID | sherpa-onnx SenseVoiceSmall INT8 2025-09-09，ONNX 模型约 226 MB | 中英日；无标点风险 |
| VAD | sherpa-onnx Silero VAD | 待确定具体 tag/hash |
| Speaker | sherpa speaker embedding ONNX | 必须先测试跨语言同一人的稳定性 |
| Speaker segmentation | sherpa-onnx / pyannote 兼容制品 | 可选后台增强；资源开销未知 |
| MT en→zh | Mozilla models through Foxlet | Foxlet 文档约 52 MB |
| MT ja→en | Mozilla models through Foxlet | Foxlet 文档约 55 MB |

这里的 MB 指静态模型参考体积，不是峰值 PSS。所有模型的**具体下载制品、哈希、许可和是否允许重新分发尚未锁定**。

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
