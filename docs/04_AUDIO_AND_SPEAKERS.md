# 04 — 音频、语言自动识别、实时声纹分离

## 音频前端

AudioRecord 单声道 PCM16，优先 16 kHz；设备不支持时实际采样率录入并重采样。采集按约 20–40 ms 帧进入有界缓冲区，另留最近 20–30 秒候选回看窗口（待测）。Silero VAD 保留前后语音 padding，限制超长句，避免开头被截断。维护 sampleOffset 半开区间、麦克风路由变化和暂停恢复事件。

## SenseVoice 与自动语言

主候选 sherpa-onnx SenseVoiceSmall INT8，language=auto；中、英、日三语为产品验收范围。重要的是：它是按段语音识别，官方 Android simulated streaming 通过部分音频重识别产生临时文本，**不是原生逐 token 的零延迟 Streaming ASR**。

- 非最终 VAD 片段可按节流间隔识别为 partial；句末再识别为 final。
- 对短词/外语插入识别不确定时允许 unknown；必要时用相邻时间证据平滑 LID，而不是将 LID 硬编码为某人身份。
- 无法在短片段可靠判断语言时仅展示原文并推迟翻译；不得把 unknown 默认为 English。
- 2025-09-09 SenseVoice INT8 粤语增强模型的官方说明：**不支持标点**。分句/标点应独立处理，或对比其他经审计模型。

## SpeakerTracker（须实现）

sherpa-onnx 提供 speaker embedding、offline diarization 与 Android 样例，但 offline diarization **不能直接当作无限时长在线跟踪**。

1. VAD 段足够长后抽取 speaker embedding；短于阈值显示 unknown。
2. 与会话级 centroid 计算余弦相似度；候选阈值靠真机数据校准而非写死宣称正确。
3. 新声音创建临时新 speaker；后续 evidence 足够后确认、合并或拆分。
4. A -> B -> A 应复用 A；同一人从日语切到中文不得自动产生 B。
5. 可选后台滑窗 segmentation 复查句内换人点，再使用 revision 回填旧字幕。
6. 对同时讲话、很相似的声线、远距离及儿童短句标出低置信，不声称能还原每个人的重叠语音内容。

## 过载行为

ASR 是高于字幕润色的优先任务；背景 speaker 修订最先降级。禁止无限 Channel 和录音线程等待锁。保留可观测队列长度与丢帧计数，确保错误不会被 UI 隐藏。

来源：[SenseVoice 预训练模型](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)、[Android Speaker Diarization](https://k2-fsa.github.io/sherpa/onnx/speaker-diarization/android.html)。
