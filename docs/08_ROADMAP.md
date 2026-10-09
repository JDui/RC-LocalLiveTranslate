# 08 — 实施路线和 Definition of Done

| Milestone | 工作 | 结束条件 |
| --- | --- | --- |
| M0 文档初始化 | 架构/技术栈/GUI/模型/质量/合规 | 文档完整，风险与未实现部分明确 |
| M1 Android 项目骨架 | Kotlin、Compose、主题、三页、Service、权限、Gradle CI | 真机安装、录音启停/权限流程可靠 |
| M2 ASR+LID | AudioRecord、VAD、SenseVoice、字幕状态 | 飞行模式中英日原文自动识别 |
| M3 离线翻译 | Foxlet、en→zh、ja→en→zh、模型导入 | 飞行模式全语言路由和哈希保护 |
| M4 在线声纹 | Speaker embeddings、聚类、unknown、异步修订 | A-B-A / 同人跨语言回归 |
| M5 使用体验 | 历史记录、横屏、可访问性、导出、调试 | Compose UI test + 权限 UX |
| M6 性能发行 | 30–60 min 热机、内存、Battery、许可、NOTICE、APK | 真机报告 + 可重现构建 |

### 次序与约束

M1→M2→M3 构成最短可用产品；M4 是最难的算法模块，不与录音线程捆绑。通过可替换接口集成，必要时先发无声纹功能的内测包，但 UI 显示 Speaker unknown 不伪造标签。

首个代码提交建议：可编译 Android Studio 工程、Version Catalog、Compose Live/Models/Settings 三页面、权限流程和假字幕数据状态，配置 assembleDebug CI；再接入 AudioRecord。未创建源码前不声称这些工作完成。默认直接在 main 工作且推送前仔细审阅。
