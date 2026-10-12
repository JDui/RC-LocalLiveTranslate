# 0.1.0-prealpha.3

## 改动

- 翻译后端替换为 Hy-MT2-1.8B 1.25-bit STQ1_0，移除 Foxlet 运行依赖。
- 实时页可选中日、中英、英日双向翻译，按语段语言标签选择另一种目标语言；第三语言只显示原文。保留默认译中文模式。
- 会话开始后固定语言选择；字幕和导出 JSON 保存目标语言，旧记录仍可读取。
- 简化界面说明文字；翻译 JNI 使用 UTF-8、固定输入/输出限制、有界 worker、取消检查及模型释放。

## 安装和升级

Android 9 / API28+，ARM64，versionCode 3。签名 APK 78,918,270 bytes（约 75.3 MiB）。下载 [版本页面](https://github.com/JDui/RC-LocalLiveTranslate/releases/tag/v0.1.0-prealpha.3) 的 `RC-LocalLiveTranslate-0.1.0-prealpha.3-arm64.apk`。沿用前两版签名身份，可覆盖升级；实际手机升级尚未验收。

APK 不包含权重，也不调用云服务。已有识别/VAD/声纹模型与会话数据可保留。旧 en-zh / ja-en 翻译模型不再使用；另行导入 `hy-mt2-tencent-hymt2-1.8b-stq1-9df5c824.zip`。权重约 440.5 MiB，包约 435.1 MiB。按 [准备说明](14_MODEL_PREPARATION.md) 在电脑生成模型包，通过 USB / SAF 离线导入。

## 验证与限制

154 项主机测试零失败；Release / AndroidTest 构建与 lintRelease 通过，0 错误 / 13 警告；发行签名及 ELF / ZIP 16 KB 对齐通过；最小 API35 模拟器中语言菜单、会话锁定、设置保留与中日字幕显示通过。固定真实权重在隔离 x86_64 验证程序中六向短句翻译通过，日志和结果见 [验证记录](11_VALIDATION.md#2026-10-12-双向语言选择)。界面演示文本不能作为模型证据。

STQ 运行内核固定在尚未合并的 llama.cpp PR #22836 提交，另有针对固定官方权重的 GGUF 类型兼容适配。六向公开短句测试不是准确率基准；模拟器加载+推理时间不是手机速度。ARM64 模拟执行曾超时，ARM64 真机、真实麦克风全链路、内存/电量/热机和长会话质量仍未验收。无 TTS。

## 构建

```powershell
./scripts/build.ps1 -Tasks @('assembleRelease','assembleDebugAndroidTest','lintRelease','--no-daemon','--max-workers=2')
./scripts/package-prealpha.ps1 -Version 0.1.0-prealpha.3
```

签名密钥与密码保存在本机忽略目录，不上传到 Git 或 Release。权重许可证 Apache-2.0；ASR 等其他权重各自条款仍适用，见 [许可矩阵](09_COMPLIANCE.md)。
