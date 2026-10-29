# 05 — 模型选择、翻译路由、离线管理

## 固定的翻译路径

- zh -> zh：原文直接显示，无翻译计算。
- en -> zh：Hy-MT2 1.8B 1.25-bit 直接译简体中文。
- ja -> zh：同一 Hy-MT2 模型直接译简体中文。
- ko / yue -> zh：同一 SenseVoice 识别并保留韩语 / 粤语标签，同一 Hy-MT2 直接译简体中文。
- unknown：延迟路由，必要时只显示原文并允许修正。

用户指定采用官方 Hy-MT2-1.8B-1.25Bit-GGUF，固定 STQ1_0 内核和权重身份。运行库及限制见 ADR-0006；不继续 M2M100 / Qwen 选型。旧 Foxlet 导入包仅保留为历史材料，不参与当前翻译。

## 初始模型候选

| 模块 | 候选制品 | 备注 |
| --- | --- | --- |
| ASR + LID | sherpa-onnx SenseVoiceSmall 原版 INT8 2024-07-17 | 本地三语 TTS auto 标签通过；2025 粤语微调候选全部返回 yue，未接受为基线 |
| VAD | sherpa-onnx Silero VAD asset 271935959 | 版本 / 发布者摘要已锁定，主机真实静音 / TTS 检查通过 |
| Speaker | 3D-Speaker CAM++ 中文 common ONNX asset 198893103 | 主机加载和 192 维向量通过；跨语言同人稳定性未验收 |
| Speaker segmentation | sherpa-onnx / pyannote 兼容制品 | 可选后台增强；资源开销未知 |
| MT zh / en / ja / ko / yue 双向 | 腾讯 Hy-MT2 1.8B 1.25-bit STQ1_0 | 权重 461,860,800 bytes，Apache-2.0；Android 实测结果另记 |

静态模型体积不是峰值 PSS。旧五套评测包的 URL / 版本 / 摘要依据与权重条款已登记，真实主机导入通过；ASR 选型依据见 [ADR-0003](adr/0003-multilingual-model-baseline.md)，详情见 [模型准备](14_MODEL_PREPARATION.md)。CAM++ 摘要为官方 HTTPS 制品的本次观察值，不是发布者提供值；全部模型的 Android 推理、真人质量及完整再分发合规尚未验收。

## 资源管理与离线保证

- 发行 APK 不含权重，设置首项“离线模型”可主动一键下载缺失模型；INTERNET用于HTTPS权重下载，另有用户主动的系统城市查询（见ADR-0008）。模型亦可经Storage Access Framework从本地文件导入，准备后可完全断网推理，见ADR-0007。
- 自定义 model manifest 记录 id、kind、version、sourceURL、sha256、size、license、requiredFiles、abi、language、quantization、benchmark。
- 解包到私有临时目录，逐项 SHA-256 校验后原子切换；错误或空间不足可恢复旧模型。
- 模型界面支持缺失/安装中/校验失败/已就绪/加载中/占用中/可删除状态；不能在正在推理时删除所需文件。
- 单个 Hy-MT2 context 按需加载，五语共用；空闲压力降级释放，恢复时重建，语段间清空 KV。
- JNI、ONNX Runtime 与 llama.cpp STQ 的 ABI/16 KB page size/so 符号冲突需要 Android 真机验证。

## 更新规则

禁止自动拉取未知模型。若后续加入模型下载器，要独立控制和展示网络权限；核心实时模式仍不得调用网络。

来源：[Foxlet Translate](https://github.com/yinvoke/foxlet-translate)、[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)、[SenseVoice](https://github.com/QwenAudio/SenseVoice)。

当前模型包准备：`./scripts/prepare-hymt-model.ps1`，已有缓存可加 `-Offline`。仅允许固定版本 `tencent-hymt2-1.8b-stq1-9df5c824`，单文件 512 MiB / 总量 1 GiB 限制不变。

## 双向语言选择

实时页使用两个语言下拉框。第一框默认自动识别，可选中文、英语、日语、韩语、粤语；第二框默认中文，可选同样五种具体语言。自动模式将所有已识别且不同于第二语言的语段译为第二语言；两个具体语言按 SenseVoice 的标签双向互译，其余语言只显示原文，未知标签不猜测方向。两个菜单相互排除另一框当前选择的具体语言，保持组合不同；底层设置读取继续兼容历史顺序。选择在新会话开始时固定，录音、暂停和启动/停止期间不可切换；菜单已打开时进入会话也立即关闭。说话人追踪不依赖语言选择。

2026-10-23 扩展至五语，同一份 Hy-MT2 权重接入二十个有向语言组合，不增加模型包。JNI 每次接收明确的 zh/en/ja/ko/yue 目标语言，重置 KV，再构造目标语言提示；粤语使用独立标签及“粤语”目标提示，不映射成中文。旧 TO_ZH / ZH_JA / ZH_EN / EN_JA 设置保留原语义及选择顺序，新组合保存两框顺序；字幕与 JSON 记录继续保存 sourceLanguage / targetLanguage，旧记录仍按原规则读取。预置演示包含五种源语言及相应目标文本，明确标记为演示。五语接入与主机合成路由测试不代表 Android 真人识别、翻译质量、性能或跨语言声纹验收。

## 快速方案评测规划

2026-10-29 新增按说话人的起始语言软偏好，并按 Astra 审核修正：首次 final 按 auto 立即送译；声纹独立确认身份后，真实语言候选接近时对最早可靠语言提出复核，须经同模型的第二遍语言假设及严格文本 / 数字一致性检查才改变标签和路由，原文及原始标点保留。明显新语言保留 auto。晚到身份也可修订当前保留窗口中的字幕，不依赖 worker 完成顺序；revision ticket 作废旧译文，防止漏译 / 方向错误与旧结果覆盖。无分数、unknown、无复核音频或预算时保留 / 恢复 auto。分句、阈值、原生补丁及真实模型局限见 [ADR-0009](adr/0009-sentence-endpoints-and-speaker-language-prior.md)。

现阶段优化现有 Hy-MT2 STQ；模型替换暂缓，不执行新后端接入。2026-10-23 已按所有者授权提高自动模式的 CPU 线程预算：生成最多 4、预填充最多 6，按核数限幅；省电和中度热压力最多 2 / 2，严重与临界保护保留。线程对照、预加载、前缀缓存、逐步显示和队列等待的分阶段规划与验收见 [翻译提速规划](21_TRANSLATION_SPEED_PLAN.md)，实际真机收益待测。
