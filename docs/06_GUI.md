# 06 — GUI 技术栈与交互规格

**决定：Kotlin + Jetpack Compose + Material 3。** Navigation Compose 管理页面，ViewModel/StateFlow 管理完整状态，状态不可变，所有耗时操作经 Repository/UseCase 到 Service/Native 层。

## 核心页面

| 页面 | 操作与内容 |
| --- | --- |
| Live | 开始/暂停/停止、拾音状态、实时字幕列表、Speaker 图例、正在处理的提示 |
| Models | 模型完整性、语言、版本、大小、许可、SHA 校验、本地导入、删除 |
| Sessions | 本地历史、重命名、SRT/JSON/纯文本导出（后续） |
| Settings | 双语/仅中文、字号、深浅主题、性能档位、保留记录开关、语言路由 |
| Diagnostics | 开发模式的队列、推理耗时、PSS 与过热状态，不记录 PCM |

## 字幕卡片契约

每条字幕显示：Speaker A/B/C/未知、稳定颜色+文字标签、源语种 ZH/EN/JA/Unknown、原文、简体中文译文（中文原话不重复）、相对时间、状态（识别中/已确认/修订）。支持临时文本渐进变成 final；修正只局部更新，避免整表跳动。只有用户位于滚动列表底部时自动滚动；向上读历史时禁止强制跳回。

说话人配色不能仅靠颜色识别，应满足正常文字 4.5:1 对比，文字标签及 TalkBack 描述必须完整；同一人跨语言颜色稳定。

## 视觉与交互

- Material 3，系统 Dynamic Color 可选、默认跟随系统暗色/浅色；优先清晰文本与低干扰操作。
- 按钮可触控范围不少于 48 dp，使用 Vector 图标；字体随系统缩放，处理折行和长句。
- 动画只表示真实录音/VAD/模型状态，使用 Compose 动画 API，遵守系统动画减少设置；不要让 20 ms 音频帧引发 UI 20 ms 重组。
- 适配横屏、分屏、平板；前台服务通知有明确停止入口。
- 错误要指出原因：麦克风拒绝、模型未安装、原生库加载失败、被其他应用占用、热降级。

## 系统整合阶段

首版先使用全屏 Live 页。系统悬浮字幕后续借 SYSTEM_ALERT_WINDOW 并单独授权；内部音频采集后续通过 MediaProjection，受播放 App 捕获策略限制，不假设能录制任意应用音频。

来源：[Compose](https://developer.android.com/develop/ui/compose)、[Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)。
