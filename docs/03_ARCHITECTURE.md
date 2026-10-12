# 03 — 模块架构与事件协议

**这是目标模块架构。0.1.0 已建立单模块 Android 工程，按包隔离采集、字幕协议、数据、模型与 GUI，已编译接入原生推理调用；实际模型运行和准确率尚未验收。拆模块时机见 ADR-0002。**

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

已实现的 SpeakerMerge 是独立会话级命令，不伪造 segmentId。声纹 worker 先发布合并，再发布具有更高 speaker revision 的逐段回填 / 拆分。ConversationStore 按 sessionId 隔离命令，SubtitleReducer 保存有界退休标签别名，拒绝旧合并与循环，延迟结果也映射到当前标签。ASR / translation revision 不受影响。声纹证据窗口不在 UI 或 Room 持久化；具体边界见 ADR-0004。

## 性能和生命周期

采集线程不能调用推理 JNI；ASR/翻译/声纹各有 Dispatcher、有界队列和可取消任务。负载超标先降低声纹复核频率，再降临时 ASR 刷新，不默默丢失整句话。模型 handle 生命周期与 Service 会话绑定；停止时先停止接收新事件，取消任务并释放 native handle。ViewModel 被重建不应使录音无故结束。

用户可选择不持久化会话。严禁将 PCM 或全量对话打到生产日志。

当前压力监测由录音 Service 持有，发布会话独立的 StateFlow；不由 UI 持有原生资源。ASR、声纹与翻译 worker 各自读取策略，可选资源按需加载和释放。禁用任务时先等待该 worker 当前调用返回，再释放其资源；临界压力立即停止采集、取消积压任务，但不强制中断 JNI。缓冲通道读取不保证挂起，因此循环及调用返回处显式检查取消。全部 worker 退出后才关闭引擎并解除模型租用。具体分档和未校准阈值见 10_DEVELOPMENT。

设置控件提交字段级变更，DataStore 在事务内只写这些字段，不用旧页面快照覆盖整份设置。关闭记录的意图在提交调用开始时生效，显示或性能更新不重置隐私意图；生产及演示会话保存都经过同一保留记录门禁。

RunDiagnostics 是会话独立的有界指标收集器，不持有音频 / 文字 / embedding。采集只调用预注册原子计数；推理 worker 记录各自调用，内存与电池由 Service 的 IO 任务采样。JSON 排序、分位数与 SAF 写入都在导出 IO 任务执行。ViewModel 固定待导出的报告引用，不承担采集或 JNI 生命周期；普通退出不持久化诊断，设备测试单独显式写报告。采样窗口、计时口径及局限见 07_QUALITY。
