# RC-LocalLiveTranslate — Agent/Contributor Rules

1. **目标永远是 Android 完全本地推理。** 不增加云端识别、云端翻译、日志上传或遥测 SDK。模型可离线导入，不以在线下载作为运行前提。
2. GUI 用 Kotlin + Jetpack Compose + Material 3；推理和采集必须与 Composable 隔离。音频线程不执行 UI 更新或模型推理。
3. Speaker ID 不能与 language ID 绑定：同一人切换中文、英语、日语仍可能是同一 Speaker。
4. 字幕事件保留 segmentId、单调时间戳、revision、语言、说话人、原文、译文，以及 partial/final/revised 状态。
5. 异步任务使用有界队列、明确优先级和取消/释放；不得因字幕积压而阻塞 AudioRecord。
6. 不向 Git 提交模型二进制、用户录音或包含私密对话的日志；模型必须登记 hash、版本、权重许可证、来源。
7. 不虚构已完成的构建、模型支持、性能和准确率。未经真机测试的数值只能叫“目标”。
8. 先看 docs/ 及 ADR 后实施；任何技术选型变更应同步更新文档、测试和许可矩阵。
9. **默认分支 main；未经所有者明确要求禁止新建分支。GitHub 推送前必须重新审阅改动，再执行提交。**
10. 代码和注释不写 emoji。GUI 使用矢量图标，不以 emoji 或颜色独自传递重要信息。

推荐次序：可编译 Android 骨架 → 麦克风/VAD/ASR/LID → 本地翻译 → 说话人在线追踪 → 浮层/记录/导出 → 真机长测和发行审核。

现阶段已有 Android 工程、Compose 折叠屏 GUI、本地数据管理和 sherpa / Foxlet 原生实时管线。构建/测试结果见 docs/11_VALIDATION.md；真实模型和真机性能尚未验收。不得将演示字幕、文件校验、合成后端管线测试或主机 UI 测试当作真实模型推理证据。
