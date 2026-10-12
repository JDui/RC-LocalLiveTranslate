# 0.1.0-prealpha.1 测试版

这是首次公开测试候选，不是已通过准确率、性能或 OEM 实机验收的稳定版本。仅支持 Android 9 / API 28 及以上的 ARM64 设备。安装包不含模型权重，也不申领 INTERNET / ACCESS_NETWORK_STATE。

2026-10-12 已公开为 GitHub 预发布（非草稿），标签指向实现提交 `4b96d34`。后续 CI 配置提交 `9e0e160` 的 Linux 构建通过；两者应用源码相同。发行与远程验证详情见 [11_VALIDATION](11_VALIDATION.md)。

## 使用

1. 从 [本仓库 GitHub Release](https://github.com/JDui/RC-LocalLiveTranslate/releases/tag/v0.1.0-prealpha.1) 下载 `RC-LocalLiveTranslate-0.1.0-prealpha.1-arm64.apk`（82,174,797 bytes，约 78.4 MiB）。允许浏览器 / 文件管理器安装此测试包；来源应为本仓库，不使用第三方改包。
2. 未准备模型时可在设置中开启演示，再到实时页开始。演示是固定字幕，明确标为演示数据，不证明识别效果，不需要麦克风。
3. 真正采集前在电脑使用 `prepare-speech-models.ps1` 与 `prepare-translation-models.ps1`，按 [模型准备](14_MODEL_PREPARATION.md) 的许可和来源说明生成五套 ZIP，经 USB / 本地文件传到设备。在模型页通过 SAF 导入。
4. SenseVoice、Silero VAD、speaker 是启动必需包；英中 / 日英包缺失时对应译文不可用。默认 ASR 基线为 2024 原版，2025 粤语候选不作为此版本基线。权重不在 Release 中分发。
5. 关闭演示，授予麦克风权限，由可见实时页开始采集。暂停 / 停止和通知停止入口可用；后台采集遵循系统前台服务限制。
6. 文本记录可关闭、查看、重命名、删除，支持 TXT / SRT / JSON 导出。原始 PCM 和 embedding 不写入长期存储。运行诊断仅在用户明确导出时写到所选文件。

## 已实现

液态玻璃视觉主题、降低透明度和字号设置、翻译魔芋自适应图标；四页在展开平屏、book / tabletop 下的功能双栏、铰链避让和紧凑回退；记录选中 / 阅读位置与可保存状态恢复；有界音频 / 推理队列、累计样本时间、输入恢复、系统压力降级；本地 VAD / ASR / LID、英译中和日英中转、独立声纹与历史标签修订；离线模型导入、校验与使用租约保护。

## 未验收与限制

- 当前没有连接 Android ARM64 实机。设备端 JNI / 模型、真实录音权限与蓝牙 / USB 路由测试尚未运行；三个 instrumentation 测试只构建。
- 142 项普通主机回归通过；实际五包的生产导入 / 租约测试另有 2 项通过。Windows x64 三语 TTS ASR / LID 检查不是 Android 真人识别准确率，译文语义和跨语言同人声纹未验收。
- 折叠适配以接近小红书的成熟体验为目标，主机布局 / 窗口 / 状态回归不等于 OEM 真机开合、动画、输入法、分屏和录音连续性验收。
- 30–60 分钟多模型热机、内存峰值、耗电和降级阈值未校准。JNI 正在执行的调用不能被强制取消；停止可能等待其返回。
- 日语经英语中转，可能损失语义；短句 / 噪声 / 相似声线 / 重叠讲话可为 unknown，声纹标签不是个人身份。拆分只能修订最近仍有证据的语段；标签累计上限包含退休标签。
- 没有内部音频、悬浮字幕、手动说话人命名、批量导出或 TTS。这些不属于首版核心范围。
- 项目自身 LICENSE 尚未选定，不为公开源码额外授予未声明的开源许可。第三方许可 / SOURCE 在设置中离线可查看；模型许可与名称 / 图标题材权利仍须独立审查。测试版不是完整合规结论。

## 构建与签名

先完成 [开发准备](10_DEVELOPMENT.md)，再运行：

```powershell
./scripts/build.ps1 -Tasks @('assembleRelease', 'testDebugUnitTest', 'lintRelease', 'assembleDebugAndroidTest', '--no-daemon', '--max-workers=2')
./scripts/package-prealpha.ps1 -Version 0.1.0-prealpha.1
```

Release APK 不可调试，不做首版未验证的 R8 混淆。签名使用本地独立 RSA 3072 位密钥；脚本允许 v2 / v3，当前 minSdk 28 制品由 apksigner 验证使用 APK v3。密钥与口令只保存在忽略目录 `.tools/signing`，首次创建时限制为当前 Windows 用户 / SYSTEM 访问。不要提交或发布该目录；维护者需要保留其受保护备份，丢失密钥会影响后续同签名升级。脚本不会覆盖已有版本附件，也不会在签名材料不完整时生成替代身份。CI 只构建 Debug 测试包，不持有该私钥，也不负责正式签名。

详尽证据与剩余门禁见 [11_VALIDATION](11_VALIDATION.md)、[13_ADAPTIVE_ACCEPTANCE](13_ADAPTIVE_ACCEPTANCE.md) 和 [08_ROADMAP](08_ROADMAP.md)。
