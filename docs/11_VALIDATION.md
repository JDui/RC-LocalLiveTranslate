# 当前发行验证记录

日期：2026-10-13（Asia/Shanghai）。当前版本0.1.0-prealpha.4 / versionCode 4。历史逐轮记录可从Git历史读取，本地原文已存入`.tools/cleanup-archive/docs/11_VALIDATION-before-prealpha4.md`；以下以当前最终制品为准。

## 构建及主机回归

最终执行`:app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintRelease :app:assembleDebugAndroidTest --no-daemon --max-workers=2`，未启用bundledModels。BUILD SUCCESSFUL in 2m 5s、132 tasks / 30 executed；testDebugUnitTest实际执行。183 tests，0 failures / errors / skipped。包含新增下载清单/许可固定身份、成功字节进度、损坏/截断/超量拒绝、取消清理，以及小文件KiB与取消按钮Compose回归。纯主机流复制测试不是网络下载证据。

Release lint：0 errors / 16 warnings，保留依赖更新及已有提示，没有隐藏错误。日志`.tools/prealpha4-final-build.log`，JUnit XML和报告在`app/build/test-results/`、`app/build/reports/`。

## 发行APK

`.tools/releases/RC-LocalLiveTranslate-0.1.0-prealpha.4-arm64.apk`，79,116,063 bytes（75.5 MiB），版本4 / prealpha.4、minSdk28、targetSdk36、仅ARM64、不可调试。项目既有签名验证通过；五个ARM64 ELF / APK ZIP 16KB、必需JNI、固定STQ来源清单均PASS。ZIP检查无ONNX/GGUF或bundled-models条目，只有13个下载清单/许可小型assets。日志`.tools/prealpha4-package.log`。

Manifest新增INTERNET用于用户主动下载权重，仍移除ACCESS_NETWORK_STATE，禁用明文流量。原生Hy network=false检查保留，不能再将无INTERNET权限作为应用隐私证据；见[ADR-0007](adr/0007-user-initiated-model-downloads.md)。

## 模拟器验证

API35 x86_64 / ARM转译、2核 / 2GiB专用模拟器。当前源码Debug+测试APK实际NativeRuntimeSmokeTest返回OK(2 tests)，JNI版本、权限以及Hy无模型拒绝通过。最终签名Release使用同签名测试APK复验NativeRuntimeSmokeTest，OK(2 tests)、0.244s。专用模拟器的旧Debug演示记录归档到`.tools/releases/emulator-demo-before-release.tar`，安装签名Release成功，冷启动为0/4。

实际点击一键下载从上游下载SenseVoice、Silero、CAM++及Hy。首轮模拟器DNS不可达，设置本机代理后通过；Hy一次连接中断，重试成功，最终4/4。实际取消/重试期间已经安装的包保留，后续只取缺项。所有文件先固定大小/SHA校验，再走生产导入器原子安装。此项是真实下载/文件校验证据，不是推理证据。

README截图来自签名Release：1080×2520 / 480dpi竖屏（21:9、360×840dp），1800×1920 / 320dpi接近方形展开屏（900×960dp），font_scale=1.0；直接Android screencap，全画面无裁切/缩放，字幕明确标记固定演示。展开屏是尺寸适配，不是实际铰链/折痕或OEM开合测试。最终截图索引在[README](../README.md)，原始UI XML为`.tools/release-ui.xml`。

## 历史模型证据及未验收项

此前同一API35的隔离x86真实Hy六向公开短句测试通过，生产导入与ARM转译原生加载亦有证据；历史ARM真实生成曾超过CPU deadline，不能推导手机推理通过。历史记录的细节见Git中的旧11_VALIDATION及[模型准备](14_MODEL_PREPARATION.md)。

仍未验收：ARM64实体手机真实音频同传、CER/WER、译文质量、跨语种同人声纹、真实折叠开合、后台/蓝牙/OEM、30–60分钟峰值内存/热机/电池、TalkBack实际朗读与精确对比度。演示字幕、下载成功、构建和主机测试不能代替这些证据。
