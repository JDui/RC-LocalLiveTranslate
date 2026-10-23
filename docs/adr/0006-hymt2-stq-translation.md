# ADR-0006 — Hy-MT2 1.8B 1.25-bit 本地翻译

日期：2026-10-12；状态：实现中，Android 实际权重验证另记。

用户指定使用腾讯 Hy-MT2-1.8B 1.25-bit，不再推进 M2M100 或通用 Qwen 方案。翻译后端采用官方 `tencent/Hy-MT2-1.8B-1.25Bit-GGUF`、固定模型 revision `9df5c824a00a744fb0512a29c640466f4d97dfb0`。权重为 461,860,800 bytes，许可证全文为 Apache-2.0。文件并非全部每权重精确 1.25 bit；使用 STQ1_0 与其他保留精度张量。

STQ 内核当前来自未合并的 llama.cpp PR #22836，固定提交 `1e411d8f5a1e23525fa3265dfb4bd76265465397`。实验依赖必须在验证记录中明确；不使用普通 master 或自动更新。编译 llama/ggml CPU 库与本项目 JNI，不编译 common、server、CLI、网络下载器或 TTS。ASR/VAD/speaker 不变，翻译由现有单 worker 执行，默认英→中、日→中直译；可选中日、中英、英日双向模式。

模型通过离线 ZIP 导入，沿用单文件 512 MiB / 总量 1 GiB、路径和完整性校验。新增 hy-mt2 ID，限定上述模型身份、STQ1_0 和许可。旧 Foxlet 文件不删除，旧翻译槽和运行依赖退出当前路径；模型目录中遗留旧文件不会被新模型使用。

兼容适配：固定官方 GGUF 的 STQ tensor type=42 / file type=41，PR 中对应 STQ type=43 / file type=42。CMake 生成限定模型名称 / 架构 / 354 tensors 的 GGUF reader 适配，原始源码 checkout 和权重字节均不修改；由导入器的固定 SHA 身份门禁保护。

每个语段清空 KV，按固定模型 Jinja 模板的单 user 格式构造提示（BOS、user marker、内容、assistant marker），避免旧内置 hunyuan-dense formatter 将 user marker 放在内容之后。上下文 1024 tokens，输入不超过 768 prompt tokens / 4096 UTF-8 bytes，输出不超过 256 tokens；60 秒 CPU abort deadline。超过界限或未到 EOG 明确失败，沿用“译文不可用”，不把截断、思考块或空输出发布为完整译文。当前选用 greedy 便于窄回归，与官方建议的随机采样参数不同，质量必须另测。

单 context 按需加载、复用，压力降级后由空闲翻译 worker 释放，恢复时重建；停止等待同步调用返回，CPU 推理设 60 秒 deadline；同步调度和模型加载的墙钟耗时不保证严格等于该上限。原生日志关闭以避免私密文本泄漏，失败转换为不含输入的错误。JNI UTF-8 byte arrays 避免补充平面字符被 Modified UTF-8 损坏。

本选择不增加 TTS，不宣称流式 ASR或逐字实时译文。官方质量、速度和手机内存宣传不是本项目实测；应补充相同中英日公开样本、手机资源及长会话验证。

来源：[官方模型](https://huggingface.co/tencent/Hy-MT2-1.8B-1.25Bit-GGUF)、[STQ PR](https://github.com/ggml-org/llama.cpp/pull/22836)。

隔离验证：hyValidation 使用 .hyvalidation 包名，显式 -PhyValidation=true 才将默认 ABI 改为 x86_64 并生成该变体 instrumentation；该属性只允许 HyValidation 任务。默认 Debug/Release/benchmark 仍为 ARM64。x86 模型推理结果不替代手机或 ARM 设备性能证据。

双向路由扩展：中日、中英、英日按 ASR 标签路由到另一种语言；所选语言之外保留原文。会话固定模式，复用同一权重和受 mutex 保护的 context；不增加 TTS 或网络调用。

2026-10-23 五语扩展：沿用相同固定权重，增加 ko / yue 识别标签及韩语 / 粤语目标提示。两个语言下拉框替代预枚举组合，支持中 / 英 / 日 / 韩 / 粤任意不同语言双向互译；第一框另有自动识别，第二框默认中文。自动模式只译至第二语言，具体语言对只路由这两种语言。旧四种模式通过兼容解析保留语义和顺序。模型 / STQ 版本、导入身份门禁及许可矩阵不变；新增语言质量与手机性能待真实音频和设备验收。

2026-10-23 资源预算更新：所有者允许更高的翻译资源占用。自动模式从固定 2 / 2 改为按核数限幅的生成最多 4 / 预填充最多 6；省电与中度热压力最多 2 / 2。线程更新由翻译 worker 在同一 mutex 下调用 `llama_set_n_threads`，无需重载权重，正在执行的语段结束后生效。严重 / 临界保护、有界队列、完成校验和 deadline 保留。后端、模型身份与 Apache-2.0 / llama MIT 许可矩阵均无变更；不包含 GPU 或 ISA 变更。具体公式、回归和真机性能边界见 21_TRANSLATION_SPEED_PLAN.md / 11_VALIDATION.md。
