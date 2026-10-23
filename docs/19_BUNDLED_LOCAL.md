# 可选本地整合构建

公开prealpha.5 Release不含模型，APP可主动下载或离线导入，参见[README](../README.md)。维护者若需完全断网安装，可使用本地已准备的四个ZIP运行：

```powershell
./scripts/build-bundled.ps1 -Version 0.1.0-prealpha.5
```

这是可选本地制品，不作为本次GitHub发行附件。首次启动通过生产导入器安装缺失模型，已有模型不覆盖。源包、权重、签名材料和APK均在忽略目录；至少预留2GiB空间。此前已完成的本地整合APK保留在`.tools/releases/`，不拿旧截图作为当前发行证据。
