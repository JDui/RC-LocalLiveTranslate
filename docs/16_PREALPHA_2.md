# 0.1.0-prealpha.2 恢复修复版

## 变化

- 修复恢复记录时、数据尚未载入导致阅读位置归零的问题；等待期间沿用保存的记录 ID，而非临时 null key。
- 补齐 tabletop 模型 / 记录页的安全分区、独立滚动、导入 / 导出入口回归与截图。
- 增加生产 MainActivity / Room 的 ActivityScenario 重建，以及延迟载入的第 15 行 / 1 px 位置回归。

当前签名候选为 `RC-LocalLiveTranslate-0.1.0-prealpha.2-arm64.apk`，82,174,797 bytes（约 78.4 MiB）。Android 9 / API 28+、ARM64；versionCode 2，与第一版签名证书一致，可覆盖安装，第一版附件不替换。公开发行状态以 [版本页面](https://github.com/JDui/RC-LocalLiveTranslate/releases/tag/v0.1.0-prealpha.2) 为准。

## 使用与边界

安装第二版 APK 后沿用本机记录 / 设置 / 模型。新设备仍需按 [模型准备](14_MODEL_PREPARATION.md) 在电脑准备并通过 USB / SAF 导入；包内无权重、无 INTERNET / ACCESS_NETWORK_STATE，也没有 TTS。没有模型时的演示是显式固定字幕，不证明识别效果。

普通主机测试 146 项零失败，完整 Debug / Release / lint / 设备测试 APK 构建通过；lint 各 0 errors / 14 warnings，新增提示是依赖版本更新通知。签名及 APK 本体静态检查通过。ActivityScenario 在 Robolectric 运行，不证明真实进程回收、OEM 开合、SAF 或录音连续性。设备测试仍仅构建。

第一版 [已知限制](15_PREALPHA.md#未验收与限制) 继续适用：真实权重 JNI、识别 / 翻译 / 声纹准确率、飞行模式推理、折叠体验与 30–60 分钟热机未验收，完整权利 / 合规审核仍未完成。测试版不关闭整体目标的这些门禁。

## 构建

完成 [开发准备](10_DEVELOPMENT.md) 后：

```powershell
./scripts/build.ps1 -Tasks @('assembleRelease','assembleDebug','testDebugUnitTest','lintRelease','lintDebug','assembleDebugAndroidTest','--no-daemon','--max-workers=2')
./scripts/package-prealpha.ps1 -Version 0.1.0-prealpha.2
```

本地密钥保护 / 备份边界不变，签名材料不得进入 Git 或 Release；丢失同签名身份将影响覆盖升级。详见 [验证记录](11_VALIDATION.md)。
