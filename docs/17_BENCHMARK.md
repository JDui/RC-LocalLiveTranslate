# 17 - GUI Macrobenchmark

## 范围与状态

新增独立 `macrobenchmark` 模块，测量无模型冷启动、固定演示字幕滚动、演示期间旋转。它不打开麦克风、不加载模型，不能证明 ASR、翻译、声纹、折叠屏 OEM 连续性或热机性能。

当前只有构建与静态校验证据，三个设备测试均 **NOT TESTED**。不计入 146 项主机测试。已公开的 prealpha.2 不包含这次测试工具改动。

## 隔离和运行前提

- 使用 Android 12 / API 31 以上 ARM64 专用测试真机，主用户 user 0；工作资料与其他用户会在测试开始前被拒绝。
- 正式包 `com.jd.rc.localtranslate` 不变，不可调试、不启用 profileable。
- 被测包 `com.jd.rc.localtranslate.benchmark` 使用 debug 签名但不可调试，只有这个变体启用 shell profiling。
- 工具包 `com.jd.rc.localtranslate.macrobenchmark` 是独立的 self-instrumenting 测试 APK，不是面向用户的发行包。
- 每次迭代 `pm clear` 只清除写死的 `.benchmark` 包，随后确认麦克风权限未授权。不要向测试包导入任何私人记录或模型。
- 不关闭已有 Perfetto 记录，也不忽略 emulator、低电量或 debuggable 等基准校验错误。测试失败时保留原因，不填入性能结果。
- 旋转测试保存并恢复系统旋转设置；恢复路径尚未经设备验证。先在专用设备执行，禁止把测试旋转当成实体折叠验证。

## 命令

Windows 本地工具链入口：

```powershell
./scripts/build.ps1 -Tasks @('assembleRelease', ':app:assembleBenchmark', ':app:lintBenchmark', ':macrobenchmark:assembleBenchmark')
./scripts/test-elf-alignment.ps1
./scripts/verify-benchmark.ps1
# 连接符合条件的测试真机后执行；当前未执行：
./scripts/build.ps1 -Tasks @(':macrobenchmark:connectedBenchmarkAndroidTest')
```

Linux / 已配置 Android SDK 的开发环境可使用同样 Gradle 任务，通过 `./gradlew` 执行。设备工具模块没有 `lintBenchmark` 任务，lint 只针对被测应用变体。

## 测量方法

所有用例使用 `CompilationMode.None()`，不等同于预编译优化发行性能。冷启动 5 次，每次在测量外重置无模型数据，测量 `StartupTimingMetric`；首帧不是模型就绪时间，也不是 fully-drawn 验收。

滚动 3 次，每次在测量外播放完整固定演示，再执行四轮上下 fling；旋转 3 次，在演示播放时切换方向。两者使用 `FrameTimingMetric`。设备屏幕尺寸、字体、系统版本、温度、电量及折叠姿态必须随结果记录。实体折叠姿态由测试人员设置，自动旋转不能替代折叠切换或小红书级流畅性验收。

基准库产生的 JSON / Perfetto traces 可能包含设备、应用和系统信息，不等同于应用的脱敏运行诊断。输出位于被忽略的构建目录，不自动上传；分享前检查内容，禁止提交私人对话、设备录音或未经审查的 traces。

## 静态门禁

`verify-benchmark.ps1` 解析三个 APK Manifest，检查包名、self-instrumenting、正式版 profiling 隔离，以及正式版与被测包没有网络/广泛存储权限；检查被测包 JNI / 无 TTS / 源码清单、工具 ARM64 ELF 和 APK ZIP 的 16 KB 对齐。

共享 ELF 校验器另有 8 个合成检查，覆盖合法对齐、4 KB、段偏移错配、非 ARM64、越界表、短表项、无加载段和截断。只证明校验器行为，不证明实际设备加载。

初版工具中的 Perfetto 1.0.0 为 4 KB 段对齐；改用 core / binary / handshake 全套 1.0.1 后，实际构建 APK 已通过上述检查。Windows Gradle 8.11.1 曾无法将缓存临时目录移到最终位置；仅恢复报错指明的完整临时项，没有清空全局缓存。

来源：[Macrobenchmark](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)、[指标定义](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics)、[Tracing 发行记录](https://developer.android.com/jetpack/androidx/releases/tracing)。
