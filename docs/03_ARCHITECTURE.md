# 03 — 模块架构与事件协议

**这是目标架构，不表示代码已经建立。**

## Android Gradle 模块

| 模块 | 职责 |
| --- | --- |
| :app | Application、Activity、权限、录音 Service、依赖装配 |
| :feature:live | 即时字幕页 |
| :feature:models | 模型版本、离线导入与错误提示 |
| :feature:sessions | 文本会话、导出 |
| :feature:settings | 性能、语言及 GUI 选项 |
| :core:audio | AudioRecord、PCM、resampler、RingBuffer |
| :core:asr | SpeechRecognizer、LID、SenseVoice 适配 |
| :core:speaker | EmbeddingExtractor、SpeakerTracker、时间窗修订 |
| :core:translate | Translator 接口、Foxlet 桥、语言路由 |
| :core:pipeline | 有界通道、任务取消、修订合并、回压 |
| :core:model | 模型清单、哈希/安装/卸载 |
| :core:data | Room、DataStore、SRT/JSON |
| :core:ui | Compose 设计系统及组件 |
| :benchmark | 离线测试音频、测试工具、性能报告（不随正式 APK） |

## 并行数据流

AudioRecord -> 16 kHz mono PCM RingBuffer -> Silero VAD -> SpeechSegments。

快路径：ASR + auto LID -> 原文(Partial/Final) -> TranslationRouter -> 字幕。

慢路径：Speaker embedding -> OnlineSpeakerTracker -> Speaker ID -> SubtitleRevision。

复核路径：最近若干秒重算 diarization / 换人点 -> 更精确的字幕时间与说话人归属（可关闭）。

## 不可省略的数据契约

- AudioFrame：sessionId、sampleOffset、sampleRate、PCM、monotonicTime。
- SpeechSegment：segmentId、[startSample,endSample) 区间、isFinal、PCM 引用。
- TranscriptEvent：segmentId、revision、sourceLanguage(zh/en/ja/unknown)、sourceText、stage(partial/final)。
- SpeakerEvent：segmentId、revision、speakerId(null 表示未知)、confidence、isCorrection。
- SubtitleItem：上述字段加 targetText、translationStatus、asrStatus、speakerStatus。

音频采样偏移为时间真值，系统墙上时钟仅用于人类时间显示。所有异步事件以 sessionId/segmentId/revision 合并，绝不可依赖列表 index。

## 性能和生命周期

采集线程不能调用推理 JNI；ASR/翻译/声纹各有 Dispatcher、有界队列和可取消任务。负载超标先降低声纹复核频率，再降临时 ASR 刷新，不默默丢失整句话。模型 handle 生命周期与 Service 会话绑定；停止时先停止接收新事件，取消任务并释放 native handle。ViewModel 被重建不应使录音无故结束。

用户可选择不持久化会话。严禁将 PCM 或全量对话打到生产日志。
