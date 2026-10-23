# 当前发行验证记录

日期：2026-10-23（Asia/Shanghai）。当前版本 0.1.0-prealpha.5 / versionCode 5。本版证据如下；后面的发行前记录和 prealpha.4 记录保留各自当时的验证范围。

## prealpha.5 两种 Release 制品

先执行 `scripts/build.ps1 -Tasks @(':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleDebugAndroidTest', '--no-daemon', '--max-workers=2')`，未启用 bundledModels。BUILD SUCCESSFUL in 3m 37s，117 tasks / 67 executed。19 suites / 197 tests，0 failures / errors / skipped；测试任务实际执行，涵盖五语路由和持久化、CPU 预算与 context 复用、取消 / 释放、压力策略、图标、折叠布局及 Compose 回归。Release lint 0 errors / 3 warnings（注释、Gradle 版本、Kapt），保留 Gradle 版本弃用提示。

执行 `scripts/package-prealpha.ps1 -Version 0.1.0-prealpha.5` 后，再执行 `scripts/build-bundled.ps1 -Version 0.1.0-prealpha.5`。整合版 BUILD SUCCESSFUL in 54s，56 tasks / 9 executed；lint 0 errors / 16 warnings，另含依赖更新提示，未关闭规则或隐藏错误。

| 制品（均在 `.tools/releases/`） | 字节数 | 模型 |
| --- | --- | --- |
| `RC-LocalLiveTranslate-0.1.0-prealpha.5-arm64.apk` | 81,082,199（77.3 MiB） | 无 bundled-models、ONNX 或 GGUF 条目；仅此 APK 作为 GitHub Release 附件 |
| `RC-LocalLiveTranslate-0.1.0-prealpha.5-arm64-bundled.apk` | 719,546,520（686.2 MiB） | 四个模型 ZIP，仅作本地整合制品 |

两包为同一 applicationId / versionCode 5，minSdk 28、targetSdk 36、仅 ARM64、不可调试，显示名「阿西牌翻译魔芋」。apksigner 验证成功，两包签名证书与本地保留的 prealpha.4 公开 APK 相同。两包五个 ELF / APK ZIP 16 KB 对齐、固定 STQ 来源、no-TTS / 必需 JNI 均 PASS，检查包含新增 `HyNative_setThreads`。从最终整合 APK 提取 SenseVoice、Silero VAD、CAM++、Hy-MT2 ZIP，通过现有 `verify-model-package.ps1` 逐项核对固定版本、大小 / SHA-256、全部清单文件完整性与来源通知。无模型打包入口实际拒绝整合 APK，未生成错误发行包。

本轮查看主机渲染的语言选择框与满幅图标预览；`adb devices -l` 无设备，未运行 instrumentation、模拟器或真实手机推理。本次构建的 AndroidTest APK 不等于设备测试已执行。模型 ZIP 完整性检查不证明本版首次启动安装、五语真实质量或性能；README 仍使用明确标注的历史 prealpha.4 模拟器图。真实音频、手机资源、热机 / 电池、OEM 折叠及跨语言声纹继续待验收。

日志：`.tools/prealpha5-standard-build.log`、`prealpha5-standard-package.log`、`prealpha5-standard-native.log`、`prealpha5-bundled-build.log`、`prealpha5-artifact-verification.log`；标准版 lint 快照为 `.tools/prealpha5-standard-lint.*`，197 项 JUnit XML 快照位于 `.tools/releases/prealpha5-host-tests/`。源码、版本、脚本及文档提交 Git，权重 / APK / 签名 / 本地日志不提交。

## 发行前及 prealpha.4 历史记录

下文保留当时“未发布”等状态描述；其中 prealpha.4 的发行记录日期为 2026-10-13（versionCode 4），不是本版设备证据。更早记录可从 Git 历史读取，本地原文曾存入 `.tools/cleanup-archive/docs/11_VALIDATION-before-prealpha4.md`。

## 2026-10-23 启动图标：魔芋主体铺满（未发布）

保留原 PNG，通过 adaptive 前景 `-12%` 比例 inset 放大主体，替换固定 6 dp 内缩；底色改为浅暖灰，主题单色轮廓同步放大。执行 `:app:assembleDebug :app:testDebugUnitTest --tests com.jd.rc.localtranslate.LauncherIconTest`，BUILD SUCCESSFUL in 49s，3 项测试通过，0 failures / errors / skipped。覆盖 manifest / 原图透明度 / 主题层、API 28 圆形裁切，以及 48 / 192 px 圆角矩形中主体不透明像素超过画布 80%、大小图占比差小于 4 个百分点、两种对话标记可见性。旧测试仅通过红通道判主体，浅色底下会失去意义，已改为检测玉绿 / 珊瑚标记。

人工查看 `app/build/ui-screenshots/launcher-konjac-rounded.png` 与 `launcher-konjac.png`。圆角矩形预览按 framework 计算的 adaptive 图层边界绘制后施加明确蒙版；实际形状由 launcher 决定，不宣称已验证 OEM 动态裁切 / 主题图标。日志 `.tools/launcher-fill-validation.log`，`git diff --check` 通过；未发布新版本。

## 2026-10-23 GUI 更新：统一小圆角与缩进（未发布）

后续按反馈减淡浅色配色：背景改为 `#F6EFDF`、面板 `#FCF7EB`、选中底色 `#EEE1C4`，其余浅色容器同步减淡。重新构建 Debug，并执行实时 / 模型 / 设置 / 记录四项页面渲染测试，4 项通过；人工复看实时和设置截图。日志 `.tools/paper-palette-validation.log`；本次仍为主机截图验证。

面板从 28–32 dp 收为 12 dp，按钮 / 输入框 / 分段控件外沿 / 导航选中项统一 8 dp，弹窗 16 dp；说话人标记使用 4 dp 圆角。正文、标题、列表与设置水平缩进统一 16 dp，面板外留白 8 dp、导航内留白 4 dp；模型与记录条目垂直留白统一 16 dp。保留 48 dp 触控目标与原有折叠区域计算。

执行 `:app:assembleDebug :app:testDebugUnitTest --tests com.jd.rc.localtranslate.ComposeUiTest --tests com.jd.rc.localtranslate.ActivityLifecycleUiTest :app:lintDebug`，BUILD SUCCESSFUL in 1m 22s。40 项 Compose 测试与 1 项 Activity 恢复测试通过，0 failures / errors / skipped，涵盖手机、横屏、平板、200% 字体、book / tabletop、弹窗避让及导航 / 阅读位置恢复。已人工查看主机渲染的手机实时页、设置页、平板侧栏及折叠区导出弹窗截图。日志 `.tools/paper-shape-validation.log`，截图 `app/build/ui-screenshots/`；`git diff --check` 通过。

本轮为主机构建、测试与截图检查，未进行模拟器 / 真机视觉验收；公开版本未更新。

## 2026-10-23 GUI 更新：纯色牛皮纸主题（未发布）

浅色采用淡黄纯色背景、米黄不透明面板与棕色控件；深色采用暖棕配色。移除背景曲线渐变和面板玻璃高光，布局及交互沿用原实现。同步更新截图辅助检查：不再要求超过 100 种颜色，而是检查绘制内容及文字语义，避免纯色记录页误报为空白。

执行 `:app:assembleDebug :app:testDebugUnitTest --tests com.jd.rc.localtranslate.ComposeUiTest :app:lintDebug`，最终 BUILD SUCCESSFUL；ComposeUiTest 40 项通过，0 failures / errors / skipped，Debug lint 0 errors / 3 warnings（SDK 注释、Gradle 版本和 Kapt 提示）。`git diff --check` 通过。日志 `.tools/paper-theme-revalidation.log`，截图 `app/build/ui-screenshots/`；已人工查看浅色 / 深色实时页、模型、设置及记录页的主机渲染截图。指定实色配对的计算对比度：浅色正文 / 背景 11.19:1、次级正文 / 面板 6.09:1、主按钮文字 6.17:1；该计算不等于所有状态及实际屏幕的完整无障碍验收。

本轮未连接设备，截图来自 Robolectric 原生绘制，非模拟器或真机截图；未发布新版本，也未验证真实模型、设备显示或 OEM 折叠行为。

## 2026-10-23 源码更新：提高翻译 CPU 预算（未发布）

自动模式按 CPU 核数限幅，生成最多 4 / 预填充最多 6 线程；省电与中度热压力最多 2 / 2。策略切换在下一句生成前更新，复用已有 context，严重与临界保护保留。具体公式见 [翻译提速规划](21_TRANSLATION_SPEED_PLAN.md)。版本号与公开 prealpha.4 Release 未更新；下面的发行记录仍属于 2026-10-13 制品。

使用 `scripts/build.ps1` 执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=2`，实际重编 ARM64 JNI。首轮新增测试有一处 `finish` 参数错误，修正后最终 BUILD SUCCESSFUL in 1m 57s，85 tasks / 26 executed。18 suites / 186 tests，0 failures / errors / skipped，包含线程预算的低核数边界、自动→省电→自动的 context 复用、翻译 worker 策略传递，以及已有取消/关闭、压力和 Compose 回归。Debug lint 0 errors / 3 warnings；既有 Gradle 版本弃用提示保留。最终日志 `.tools/translation-resource-final-build.log`，JUnit 在 `app/build/test-results/testDebugUnitTest/`。

`scripts/verify-native.ps1` 检查本轮 Debug APK：五个 ARM64 ELF / APK ZIP 16 KB 对齐、必需 JNI、固定 STQ 来源清单全部 PASS；`llvm-nm` 确认原生库导出新增 `HyNative_setThreads`。`git diff --check` 通过。APK 为 `app/build/outputs/apk/debug/app-debug.apk`。

`adb devices -l` 无连接设备，本轮未运行 instrumentation、真实模型或真机性能测试。上述合成后端、主机测试与符号检查不证明新线程组合的实际加速、翻译质量、热机、电池、内存或 ASR 同时运行表现；编译出的 AndroidTest APK 也不等于设备测试已执行。

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

## 2026-10-23 韩语 / 粤语及两个语言选择框

沿用固定 SenseVoiceSmall INT8 2024-07-17 和 Hy-MT2 1.8B 1.25-bit 权重，接通独立 ko / yue 标签、韩语 / 粤语 JNI 目标提示及独立翻译诊断分类。语言模式改为两个字段，保留旧四种设置和历史字幕读取；第一框默认自动识别，第二框默认中文。具体语言按标签双向路由，其他语言只显示原文，unknown 不猜测；选择相同语言时交换两框。菜单在启动 / 录音 / 暂停 / 停止期间锁定并关闭。五语固定演示仍标明演示数据。

最终通过 `scripts/build.ps1` 执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=2`，ARM64 JNI 已重编。BUILD SUCCESSFUL in 1m 48s，85 tasks / 26 executed；19 suites / 197 tests，0 failures / errors / skipped。覆盖五语二十个有向组合、自动模式五个目标、旧设置兼容及全部选择顺序持久化、粤韩标签 / 字幕 JSON、合成后端粤韩互译及诊断、两个选择框与相同语言交换、会话锁定和菜单关闭、320 dp / 200% 字号。首轮界面测试误点背景同名按钮，改为菜单项唯一 tag 后通过；没有放宽功能断言。Debug lint 0 errors / 3 existing warnings，Gradle 版本弃用提示保留。

已查看主机原生图形渲染 `app/build/ui-screenshots/five-language-selectors.png` 和 `five-language-narrow-large-font.png`，两个选择框可见且无重叠。`scripts/verify-native.ps1` 对本轮 Debug APK 的五个 ARM64 ELF、APK ZIP 16 KB 对齐、必需 JNI 与固定 STQ 来源检查全部 PASS；`git diff --check` 通过。日志为 `.tools/five-language-final-build.log` 和 `.tools/five-language-native-check.log`，APK 为 `app/build/outputs/apk/debug/app-debug.apk`。本轮未提交、推送或更新公开 Release。

`adb devices -l` 无连接设备，未运行 instrumentation 或真实音频 / 模型推理；测试 APK 编译、主机 UI 渲染及合成后端结果不构成 Android 五语识别 / 翻译准确率、混说、性能、热机或跨语言声纹证据。
