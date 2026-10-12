# ADR-0003 — 多语评测基线使用原版 SenseVoice INT8

日期：2026-10-09；状态：Accepted for local evaluation，Android / 真人准确率待验收。

## 要求与证据

产品要求自动中文 / 英语 / 日语语言识别，不能靠强制指定语种代替。原候选 2025-09-09 是由官方 sherpa 转换的 ASLP-lab 粤语微调版；[上游说明](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html) 明确其粤语微调来源和无标点限制。支持语言的上游说明不是本项目自动路由已通过的证据。

Windows x64 sherpa-onnx 1.13.8、CPU 2 threads、auto / ITN=true、16 kHz，在三段预先固定的本地 Windows TTS 上对比：

| 输入 | 2025-09-09 auto 标签 | 2024-07-17 auto 标签 |
| --- | --- | --- |
| 英语 | yue，失败 | en，通过 |
| 普通话 | yue，失败 | zh，通过 |
| 日语 | yue，失败，文本明显偏离 | ja，通过 |

没有改断言、手动提示语种、用字符脚本猜语言或把 yue 映射成 zh 来消除失败。相同输入 / 相同配置下，2024 原版能提供本项目需要的三语自动路由。因此默认本地模型准备改为原版 **sherpa INT8 2024-07-17**，不是按发布日期新旧或能否编译选择。

## 决定与限制

当前 `speech-models.lock.json.models` 采用 2024 基线，`asrAlternatives` 保留 2025 的来源、校验与拒绝原因。2024 源文件 SHA-256 及官方压缩包发布者摘要固定，权重字节不变；包保留 FunASR Model License 1.1，不当作 MIT / Apache 权重。2025 额外模型卡 Apache 声明和底座条款均保留，公开分发仍待审核。

三段 TTS 不足以给出真人 CER / WER、短句 LID、噪声 / 混说或 Android 功耗结论。下一步仍需真实数据和设备；未来替换模型必须使用同样的自动语种、准确率 / 延迟 / 资源及许可验收，不将这个窄检查扩张成生产准确率承诺。失败 / 成功报告及复现命令见 [14_MODEL_PREPARATION](../14_MODEL_PREPARATION.md)。
