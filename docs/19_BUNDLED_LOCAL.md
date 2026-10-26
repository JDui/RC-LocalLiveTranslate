# 四模型整合构建

正式版 0.3.3 公开 Release 携带四个模型，可完全离线准备，参见 [README](../README.md)。使用本地已准备的四个 ZIP 运行：

```powershell
./scripts/build-bundled.ps1 -Version 0.3.3
```

生成 `RC-LocalLiveTranslate-0.3.3-arm64-bundled.apk`，作为本次 GitHub 正式发行附件。首次启动通过生产导入器安装缺失模型，已有模型不覆盖。源包、权重、签名材料和 APK 均在忽略目录；至少预留 2 GiB 可用空间。普通构建及 `package-release.ps1` 生成无模型版，保留在本地 `.tools/releases/`，不作为本次公开附件。旧版本制品保留，README 截图按本次签名发行包重新拍摄。
