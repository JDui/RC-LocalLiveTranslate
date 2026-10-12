# 本地模型整合 APK

`./scripts/build-bundled.ps1` 将已准备的 SenseVoice、Silero VAD、CAM++ 与 Hy-MT2 ZIP 作为不重复压缩的 assets 打入 Release APK，并沿用现有签名。模型与产物仍仅位于忽略目录 `.tools`，不提交 Git 或发布到 GitHub。普通 APK 构建不包含权重。

首次启动在 IO 协程中直接读取内置 ZIP，经生产导入器执行路径、大小、摘要、版本和许可清单检查，原子安装到私有目录。安装期间不能开始拾音；已安装的模型不覆盖。中途退出可在下次启动继续缺失模型；删除内置模型后，下次启动会恢复。模型文件仍可离线导入替换。

输出：`.tools/releases/RC-LocalLiveTranslate-0.1.0-prealpha.3-arm64-bundled.apk`。与轻量版包名、versionCode 及签名一致，可覆盖安装；本地整合版启动行为与公开 prealpha.3 源码不同。首次安装需要 APK 本体及展开权重的空间，建议手机至少预留 2 GB。内置包仍保留各自完整 LICENSE / SOURCE；仅供本地测试，不能视为所有权重已完成公开分发审核。

## 本次验证

- 签名 APK 717,398,975 bytes（684.2 MiB），版本 0.1.0-prealpha.3；四个 ZIP 已进入 assets，签名、原生清单、ELF / ZIP 16 KB 对齐通过。
- 156 项主机测试通过，0 失败/错误；包含安装校验、内置包 ID 拒绝、失败重试和已有模型保留。Release / lintRelease 构建通过，lint 0 错误 / 13 警告。
- 最小 API35 / 2核 / 2048 MiB 模拟器覆盖安装签名整合包成功。已有轻量版未导入模型，首次打开自动安装全部四包；实时页显示“4 个模型已安装”，模型页 4/4，Hy-MT2 固定版本和校验状态可见。强制停止并重启后仍为 4/4，crash buffer 为空。
- 实际画面 `.tools/emulator-validation/38-bundled-installed-models.png` 已检查；Hy 安装状态图为 `39-bundled-hymt-installed.png`。早期 `36-bundled-installing.png` 因 UIAutomator 初次读取返回空 root，不作为安装进度证据。
- 本次证明自动安装和重启保留，不增加真机性能、真实麦克风同传或模型质量验收结论。源代码改动与整合包仅在本地，未更新公开发行。

UI修正版构建支持 `./scripts/build-bundled.ps1 -OutputFile .tools/releases/RC-LocalLiveTranslate-0.1.0-prealpha.3-arm64-bundled-ui2.apk`。最终UI和安装复查见 [20_UI_REVIEW](20_UI_REVIEW.md)。已存在产物不覆盖；模型资产仍不进入Git。
