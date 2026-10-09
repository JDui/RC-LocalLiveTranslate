# 09 — 第三方许可证、隐私与系统权限

## 许可证矩阵（发行前必须复查具体制品）

| 依赖 | 已核实的上游声明 | 必须行动 |
| --- | --- | --- |
| sherpa-onnx 核心 | Apache-2.0 | 保留适用版权/NOTICE，核对原生依赖 |
| Foxlet 原创代码 | MIT | AAR 中第三方组件另有许可 |
| Bergamot/Mozilla MT 权重 | Foxlet NOTICE 声明 MPL-2.0 | 保留模型许可/来源及适用源码取得方式 |
| SenseVoice 源码 | MIT | 不等于权重许可证 |
| SenseVoice 权重 | 官方模型卡指向 FunASR Model Open Source License | 审核最终 ONNX 转换包与署名条款 |
| Speaker 模型 | 按具体模型卡决定 | 禁止未审核权重直接随 APK 发布 |
| 本仓库源代码 | 尚未设定项目 LICENSE | 发行前由仓库所有者决定 |

参考：[Foxlet LICENSE](https://github.com/yinvoke/foxlet-translate/blob/main/LICENSE)、[Foxlet NOTICE](https://github.com/yinvoke/foxlet-translate/blob/main/NOTICE)、[SenseVoice LICENSE 指引](https://github.com/QwenAudio/SenseVoice)。暂不自动为本仓库添加任何许可证。

## 离线、存储及隐私

默认不需要 INTERNET、无用户账户/遥测/云端推理。仅将字幕记录留本机 Room（可关闭/删除）；原始 PCM 不写入永久存储也不写日志；音频临时窗口会释放。Speaker A/B 仅是当前会话聚类标签，不是真实身份证明或跨会话追踪指纹。

## Android 权限

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
