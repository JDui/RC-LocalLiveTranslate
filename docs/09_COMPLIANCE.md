# 09 — 第三方许可证、隐私与系统权限

## 许可证矩阵（发行前必须复查具体制品）

| 依赖 | 已核实的上游声明 | 必须行动 |
| --- | --- | --- |
| sherpa-onnx 核心 | Apache-2.0 | 保留适用版权/NOTICE，核对原生依赖 |
| 无 TTS JNI 的原生传递组件 | Kaldi / OpenFST / simple-sentencepiece Apache-2.0、KissFFT BSD-3-Clause、nlohmann JSON MIT、Eigen 5.0.1 MPL-2.0 及其兼容第三方条款 | 全文、公开源码版权行和固定 SOURCE 已随包保留；Eigen 未修改，保留对应源码取得方式 |
| AndroidX Media3 common 1.11.1 / Sonic | Apache-2.0 | 随包保留全文及 AOSP / Bill Cox 署名，发行前核查传递依赖 |
| llama.cpp / ggml STQ runtime | MIT，固定 PR #22836 提交 | 保留全文与 SOURCE；不编译网络工具，实验 PR 状态见 ADR-0006 |
| 腾讯 Hy-MT2 1.8B 1.25-bit 权重 | 固定 revision 的 LICENSE.txt 为 Apache-2.0 | 本地包保留版权、完整许可、来源、发布者摘要；权重不提交 Git / APK |
| 旧 Bergamot/Mozilla MT 权重（退出当前运行路径） | Foxlet NOTICE 与 Mozilla 维护者声明 MPL-2.0；英中 2.2 / 日英 2.1 制品已锁定 | 本地包保留许可全文 / SOURCE；公开分发前复核适用源码取得方式 |
| SenseVoice 源码 | MIT | 不等于权重许可证 |
| SenseVoice 2024 基线权重 | 官方模型卡指向 FunASR Model License 1.1，全文和来源已随本地包保留 | 自定义权重条款；完整再分发审核未完成，不当作 MIT / Apache |
| Silero VAD asset 271935959 | 官方 MIT 声明，固定原文随本地包保留 | 保留 Silero Team 版权，复核实际发行制品 |
| CAM++ speaker asset 198893103 | iic 官方模型卡声明 Apache License 2.0，许可 / 作者 / 来源保留 | 同人跨语言未验证，完整发行审核仍需完成 |
| 本仓库源代码 | 尚未设定项目 LICENSE | 发行前由仓库所有者决定 |

参考：[Foxlet LICENSE](https://github.com/yinvoke/foxlet-translate/blob/main/LICENSE)、[Foxlet NOTICE](https://github.com/yinvoke/foxlet-translate/blob/main/NOTICE)、[SenseVoice LICENSE 指引](https://github.com/QwenAudio/SenseVoice)。暂不自动为本仓库添加任何许可证。

五套模型的具体来源、固定许可提交与本地包保留项见 [14_MODEL_PREPARATION](14_MODEL_PREPARATION.md)。这些包仅在忽略目录准备，不捆绑或公开发布；完整再分发许可仍未审核完成。2025 粤语微调候选保留其 Apache 模型卡声明及 FunASR 底座条款，但未接受为三语 auto 基线。

## 离线、存储及隐私

发行审查补充：固定 sherpa v1.13.8 的 `build-android-arm64-v8a.sh` 默认 `SHERPA_ONNX_ENABLE_TTS=ON`，CMake 会引入 piper-phonemize / espeak-ng，不能只依据 sherpa 主 LICENSE 宣称整个预编译 JNI 均为 Apache-2.0。本应用不调用 TTS 不等于对应代码未链接。现已改为固定源码、关闭 TTS / 离线 diarization 的构建；APK 导出检查确认无这些入口，ASR / VAD / speaker embedding 保留。原生传递依赖的 26 份许可 / 版权 / NDK / SOURCE 文件已随包保留，Eigen 源码取得方式明确，详见 ADR-0005。这解决了已识别的无关 TTS 分发问题，不替代所有依赖的最终法律审查。来源：[固定构建脚本](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/build-android-arm64-v8a.sh)、[固定 CMake](https://github.com/k2-fsa/sherpa-onnx/blob/11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf/CMakeLists.txt)、[上游许可边界讨论](https://github.com/k2-fsa/sherpa-onnx/issues/3731)。

原生准备脚本保留 sherpa-onnx 的 Apache-2.0 全文、ONNX Runtime 1.28.2 的 MIT 与 ThirdPartyNotices，以及 Foxlet 0.5.0 AAR 内 NOTICE、SOURCE、完整聚合第三方许可。应用设置页可离线查看这些文件；Foxlet MPL 源码获取信息保留在 SOURCE / NOTICE。这不是完整发行合规结论：实际权重、sherpa 原生附带组件、最终运行库版本及项目自身许可证仍须逐项核查。SDK 不带模型权重。

本地重采样只使用 Media3 common，不引入 ExoPlayer / 数据源模块。显式移除 common 清单带来的 ACCESS_NETWORK_STATE，应用不需要读取网络状态。`assets/licenses/media3-1.11.1` 保留上游 Apache-2.0 全文和源码路径，包括 Sonic 的 AOSP 与 Bill Cox 版权声明；传递依赖尚未作为完整发行清单验收。来源：[固定版本 Sonic](https://github.com/androidx/media/blob/1.11.1/libraries/common/src/main/java/androidx/media3/common/audio/Sonic.java)。

默认不需要 INTERNET、无用户账户/遥测/云端推理。仅将字幕记录留本机 Room（可关闭/删除）；原始 PCM 不写入永久存储也不写日志；音频临时窗口会释放。Speaker A/B 仅是当前会话聚类标签，不是真实身份证明或跨会话追踪指纹。

运行诊断仅保留有界内存统计，不自动写文件；用户显式 SAF 导出后才写入选定位置。设备测试工具另在测试应用私有目录写报告。字段包括机型 / SoC / SDK、运行库版本、既有模型预期哈希、耗时、队列计数、内存、热状态及电池，不包含序列号 / Android ID、PCM、字幕、译文、embedding、来源 URL、私有路径或异常消息。导出的机型和性能信息仍可能关联设备，分享前应审阅；诊断不新增权限或网络访问。

启动器图标通过内置图像生成工具原创制作，不随包复制漫画、角色截图或现成图标。生成与资源记录见 `12_ICON.md`；这不等于已完成「翻译魔芋」题材相关名称、商标及发行权利审查，不替项目指定许可证。

## Android 权限

GUI Macrobenchmark 使用独立测试包和独立目标包，正式应用不新增依赖或权限。工具产生的 Perfetto traces 可能包含系统/设备信息，不自动上传，分享前须审阅；测试包数据会被每轮清除。详细边界见 [17_BENCHMARK.md](17_BENCHMARK.md)。

| 操作 | 相关权限与限制 |
| --- | --- |
| 麦克风 | RECORD_AUDIO 运行时授权 |
| 持续后台录音 | FOREGROUND_SERVICE、FOREGROUND_SERVICE_MICROPHONE、service type microphone；需遵守 Android 14+ 从前台启动、使用期间权限和通知限制 |
| Android 13+ 通知 | 相关 POST_NOTIFICATIONS 权限和拒绝后的 UI |
| 悬浮字幕（后续） | SYSTEM_ALERT_WINDOW 单独授权，不影响普通全屏使用 |
| 内部音频（后续） | MediaProjection + AudioPlaybackCapture，音源必须允许抓取 |
| 模型导入 | SAF 选择本地文件，无需广泛存储权限 |

Android 受限制的麦克风前台服务一般不能从任意后台状态/BOOT_COMPLETED 创建；用户必须通过可见界面明确开启录音。停止入口始终可达。监测 Android 17 的 App memory limit 新行为。

来源：[Android mic foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Audio playback capture](https://developer.android.com/media/platform/av-capture)、[Android 17](https://developer.android.com/about/versions/17)。

当前发行依赖已移除 Foxlet AAR。旧许可材料及模型目录不主动删除；历史验证段中的 Foxlet 信息不表示当前 APK 仍加载该引擎。Hy-MT2 的模型许可不替代本项目许可，STQ 运行库为 MIT。
