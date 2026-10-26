# 当前发行验证记录

日期：2026-10-26（Asia/Shanghai）。当前版本 0.3.3 / versionCode 303，按所有者要求作为正式版发布，公开附件改为四模型整合 APK。下文历史段落的“未发布”“不提交”等描述保留其当时语境；prealpha.6 仅创建草稿，未公开发布。

## 0.3.3 构建与回归

无模型配置执行 `scripts/build.ps1 -Tasks @(':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleDebugAndroidTest', '--no-daemon', '--max-workers=2')`，BUILD SUCCESSFUL in 3m 17s，117 tasks / 36 executed。21 suites / 220 tests，0 failures / errors / skipped，含 59 项 Compose 测试。覆盖衬线字体切换及持久化、大字体与铰链布局、按钮嵌套几何、重命名 / 删除 / 空态及已有声纹 / 五语管线回归。Release lint 0 errors / 16 warnings，保留原生注释、Kapt 与版本提示。

无模型版使用新通用 `package-release.ps1 -Version 0.3.3` 签名；旧 `package-prealpha.ps1` 保留兼容转发。原项目签名不更换，权重、APK 和签名材料不提交 Git。

四模型配置执行 `scripts/build-bundled.ps1 -Version 0.3.3`，BUILD SUCCESSFUL in 1m 54s，56 tasks / 9 executed，Release lint 0 errors / 16 warnings。两包 minSdk 28、targetSdk 36、ARM64、不可调试；签名验证通过，且与重新下载的旧公开 prealpha.5 APK 证书一致。五个 ELF 和 APK ZIP 的 16 KB 对齐、必需 JNI、no-TTS 与固定 STQ 来源门禁全部通过。最终整合 APK 中恰有四模型 ZIP，分别提取后用 `verify-model-package.ps1` 验证固定版本、文件大小 / SHA-256、完整文件清单及来源通知，全部通过；无模型 APK 无模型 ZIP、ONNX 或 GGUF。

| 制品（`.tools/releases/`） | 字节数 | 发行范围 |
| --- | --- | --- |
| `RC-LocalLiveTranslate-0.3.3-arm64.apk` | 81,180,503（77.4 MiB） | 无模型，本地保留 |
| `RC-LocalLiveTranslate-0.3.3-arm64-bundled.apk` | 719,644,824（686.3 MiB） | 四模型，唯一公开附件 |

本轮使用独立 `RC_LLT_Release033_API35` AVD（API 35 x86_64，ARM64 翻译，2 核 / 2 GiB），保留既有审查模拟器的应用与数据。无模型签名 Release APK 全新安装为 0/4；同签名 AndroidTest 的 `NativeRuntimeSmokeTest` 为 OK（2 tests，0.312 秒），验证 sherpa 1.13.8 / ORT 1.28.2 JNI、下载权限及 Hy JNI 缺失模型拒绝路径，不是实际模型推理。

同签名覆盖安装本版整合 APK 后，生产导入器安装四模型并显示 4/4；整合包再执行同组 JNI 冒烟为 OK（2 tests，0.281 秒）。只验证文件安装和 JNI 路径，未执行真人语音模型推理。

README 的 11 张图均重新拍摄本版签名 APK：主界面的 10 张来自整合版，0/4 下载入口来自无模型版。手机 1080×2520 / 480 dpi、展开 1800×1920 / 320 dpi，系统字体 100%；保留原始完整 PNG，无图像裁切 / 缩放。人工逐张查看，图片尺寸、同期 UIAutomator 标签及各页 Dock 坐标通过脚本检查。字幕与记录明确为演示，不构成真实推理；运行页保留未监测 / 不可用。证据在 `.tools/release033-screenshots/` 和 `release033-screenshot-verification.log`。本版未执行 ARM64 实体手机、真实模型质量 / 延迟、长期热机 / 电池或 OEM 折叠验收。

本地日志：`.tools/release033-standard-build.log`、`release033-standard-package.log`、`release033-standard-smoke.log`；lint 快照 `.tools/release033-standard-lint.*`，220 项测试 XML 在 `.tools/releases/release033-host-tests/`。

整合日志 `.tools/release033-bundled-build.log`、`release033-bundled-smoke.log`；模型完整性见 `release033-artifact-verification.log`。初次签名比较因本地旧 prealpha.4 文件已不存在而中断；改用从 GitHub 重新下载的公开 prealpha.5 进行独立证书比较，通过记录在 `release033-identity-verification.log`，制品元数据在 `release033-apk-results.json`，没有重复进行已通过的模型摘要校验。

## prealpha.6 及发行前历史记录

## 2026-10-26 衬线字体设置（源码更新，未发布）

设置“显示”区新增默认开启的“衬线字体”，旧 DataStore 缺少该键时也默认开启。全局 Material Typography 的 15 个文本样式统一选择系统 Serif / SansSerif；不引入字体下载或字体资产。字段级设置更新、整份设置写入及重新读取均覆盖新字段。

执行 `:app:assembleDebug :app:testDebugUnitTest --tests com.jd.rc.localtranslate.ComposeUiTest --tests com.jd.rc.localtranslate.LocalDataTest :app:lintDebug`，BUILD SUCCESSFUL in 1m 22s。48 项 Compose 与 13 项本地数据测试通过，0 failures / errors / skipped；新增测试检查默认开启、双向切换后的实际文本布局字体族、持久化恢复及修改其他字段时保持字体和隐私设置。已查看主机设置页截图 `app/build/ui-screenshots/settings.png`；日志 `.tools/serif-font-validation.log`。本轮未进行真机字体 / OEM 字形回退验收，也未更新公开 Release。

## prealpha.6 两种 Release 制品与回归

执行 `scripts/build.ps1 -Tasks @(':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleDebugAndroidTest', '--no-daemon', '--max-workers=2')`，未启用 bundledModels。BUILD SUCCESSFUL in 3m 15s，117 tasks / 43 executed。21 suites / 207 tests，0 failures / errors / skipped；覆盖声纹提前调度、取消 / 释放、稳定配色、城市结果与手动修改的竞争、导航与滑动删除，以及已有五语和 Compose 回归。

执行 `scripts/package-prealpha.ps1 -Version 0.1.0-prealpha.6`，再执行 `scripts/build-bundled.ps1 -Version 0.1.0-prealpha.6`。整合版 BUILD SUCCESSFUL in 1m 39s，56 tasks / 9 executed。两次 Release lint 均为 0 errors / 16 warnings（注释、Gradle / 依赖版本和 Kapt 提示）；编译另有城市查询使用 AndroidX CancellationSignal / getCurrentLocation 的两项弃用警告及既有 Gradle 版本提示，未隐藏错误。

| 制品（均在 `.tools/releases/`） | 字节数 | 模型 |
| --- | --- | --- |
| `RC-LocalLiveTranslate-0.1.0-prealpha.6-arm64.apk` | 81,164,119（77.4 MiB） | 无 bundled-models、ONNX 或 GGUF；仅此 APK 作为公开 Release 附件 |
| `RC-LocalLiveTranslate-0.1.0-prealpha.6-arm64-bundled.apk` | 719,628,440（686.3 MiB） | 四个模型 ZIP，仅保存在本地 |

两包 applicationId 相同，versionCode 6、minSdk 28、targetSdk 36、仅 ARM64、不可调试，显示名「RC翻译魔芋」。apksigner 验证通过，签名证书与保留的 prealpha.4 公开包相同。两包五个 ELF / APK ZIP 16 KB 对齐、固定 STQ 来源、no-TTS / 必需 JNI（包含 `HyNative_setThreads`）全部 PASS。从最终签名整合 APK 提取四个模型 ZIP，使用 `verify-model-package.ps1` 验证固定版本、大小 / SHA-256、全部清单文件与来源通知，均通过。

## prealpha.6 模拟器与 README 截图

使用现有 API 35 x86_64 / ARM64 翻译模拟器，以只读 AVD 启动，2 核 / 2 GiB，不改写原 AVD 数据。无模型版全新安装显示 0/4；同签名覆盖安装整合版并启动，生产导入路径完成四模型安装，界面显示 4/4。再覆盖安装无模型版，已安装模型和演示记录保留。为本版无模型签名 Release APK 安装同签名测试 APK，执行 `NativeRuntimeSmokeTest`，OK（2 tests，0.1 秒）：sherpa 1.13.8 / ONNX Runtime 1.28.2 JNI 加载、下载权限及 Hy JNI 缺失模型拒绝路径通过。

README 的 11 张图均由本版无模型 Release APK 直接 `screencap`，保留原始 PNG，无裁切 / 缩放；系统字体 100%，手机 1080×2520 / 480 dpi，展开尺寸 1800×1920 / 320 dpi。覆盖字幕、记录、模型、设置、运行与诊断及全新安装下载入口。已逐张人工查看，并核对图片尺寸、README 引用与同期 UIAutomator 层级。字幕 / 记录来自明确标注的固定演示；4/4 仅证明本地模型文件安装与保留，不是实际推理。运行页的未监测 / 不可用状态没有替换成模拟性能数值。

本轮无 ARM64 实体手机测试，也未执行真人音频模型推理、真实城市查询、OEM 折叠开合或长期热机 / 电池测量；翻译模拟器 JNI 冒烟不证明真实模型质量、延迟或设备性能。

本地证据：`.tools/prealpha6-standard-build.log`、`prealpha6-standard-package.log`、`prealpha6-bundled-build.log`、`prealpha6-artifact-verification.log`、`prealpha6-apk-results.json`、`prealpha6-release-smoke.log`；lint 快照 `.tools/prealpha6-standard-lint.*`，207 项 JUnit XML 在 `.tools/releases/prealpha6-host-tests/`，原始 PNG / UI 层级在 `.tools/prealpha6-screenshots/`。权重、APK、签名与本地日志不提交 Git。

## prealpha.5 历史发行验证

日期：2026-10-23（Asia/Shanghai），版本 0.1.0-prealpha.5 / versionCode 5。以下为当时证据，不替代本版验证。

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


## 2026-10-26 声纹提前调度、城市与设置 / Dock 整理

实现：VAD 检出后以约 0.8 秒证据首次尝试声纹，不等待 ASR / 句末；首份清晰证据分配标签，未决最多每秒重试，仍保留原生就绪检查、歧义 unknown、容量 4 队列、短段限制和有界修订。每位 Speaker 使用稳定独立色相，原文 / 译文混入 33% 对应色；浅 / 深背景、面板与标记背景的文字对比度低于 4.5:1 时调整。设置新增主动手机城市查询，申请前台大致位置，通过系统 Geocoder 取城市；不保存坐标，失败保留手动输入，晚到结果不能覆盖手动保存。直辖市优先于行政区名。

主导航为字幕 / 记录 / 设置；模型为设置首项子页，运行与诊断合并子页，返回及状态恢复有回归。语言菜单相互排除，文本与箭头紧凑居中。记录第一次左滑只展开删除，第二次独立左滑删除；保留按钮确认路径及向右收起。追加要求的 Dock：同一窗口 / 姿态下各页和子页保持坐标与尺寸，展开平屏左侧栏的设置单独固定在底部，上方两项独立滚动。

验证分三轮保留日志：

- 全量：`scripts/build.ps1` 执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=2`，BUILD SUCCESSFUL in 2m 4s，85 tasks / 28 executed；21 suites / 205 tests，0 failures / errors / skipped。日志 `.tools/speaker-settings-full-build.log`。覆盖提前 embedding 不等待 ASR、A/B/A、未知 / 有界声纹修订、字幕回填、16 种配色及浅深背景对比、城市字段 / 时效 / 权限拒绝 / 原子写入、两次滑动 / 右滑、设置子页恢复、语言互斥和折叠避让。
- 直辖市补强后定向：同样构建 / lint / 测试 APK，加 `testDebugUnitTest --tests '*DeviceCityResolverTest'`；4 tests，0 failures / errors / skipped，BUILD SUCCESSFUL in 1m 17s。日志 `.tools/speaker-settings-final-build.log`。
- Dock 追加后定向：同样构建 / lint / 测试 APK，加 `testDebugUnitTest --tests '*ComposeUiTest'`；47 tests，0 failures / errors / skipped，BUILD SUCCESSFUL in 1m 33s，85 tasks / 24 executed。包含紧凑窗口各页 Dock 几何一致，以及 900 dp 展开屏切换字幕 / 记录 / 设置 / 模型 / 诊断时 Dock 与各图标坐标一致，设置固定底部。日志 `.tools/speaker-settings-dock-build.log`。

最终 Debug lint：0 errors / 16 warnings，均为既有原生注释、kapt 及 Gradle / 依赖更新类别；前一轮报告为 3 项，最终报告额外列出 13 项依赖更新提示，没有修改依赖版本或隐藏警告。Gradle 版本弃用提示保留。最终 APK 重新执行 `scripts/verify-native.ps1`，五个 ARM64 ELF、APK ZIP 的 16 KB 对齐、必需 JNI、无 TTS 和固定 STQ 来源门禁全部 PASS，日志 `.tools/speaker-settings-native-check.log`。`git diff --check` 通过。

已人工查看主机原生图形截图：`live-expanded.png`、`live-dark.png`、`five-language-narrow-large-font.png`、`settings.png`、`settings-dual-screen.png`、`settings-runtime-fold.png`、`session-swipe-revealed.png`、`dock-expanded-model-settings.png`，均位于 `app/build/ui-screenshots/`。截图证据为固定演示 / 合成数据，不是 Android 设备实拍。最终调试 APK：`app/build/outputs/apk/debug/app-debug.apk`；测试 APK 已编译，未运行 instrumentation。

`adb devices -l` 仍无连接设备。未验收：真人 CAM++ 的短句 / 相似声线 / 跨语种准确率、实际首次标签耗时、句内连续换人分割、重叠语音、真实城市定位与 OEM 地理编码、实际折叠开合及持续录音。0.8 秒是软件首次尝试门槛，不是实测端到端延迟。系统城市服务可能联网，见 ADR-0008；语音推理不依赖该服务。本轮未提交、推送或更新公开 Release，保留工作区并行的应用显示名修改。

## 2026-10-26 UI 全流程严格审查

追加按钮圆角 / 嵌套边缘专项：录音主按钮上下右各 4 dp 等距内缩，外框 12 dp / 控件 8 dp，同层控制面板与横向导航左右同边且间隔 8 dp；工具按钮的实际按下反馈及城市 / 滑动删除按钮显式小圆角。Live 截图矩阵新增几何断言，原 360 dp 小屏字幕区 >=450 px 断言保留。最终日志 `.tools/ui-audit-button-final2.log`：BUILD SUCCESSFUL in 1m 37s，220 tests（Compose 59），0 failures / errors；lint 0 errors / 16 warnings。`.tools/ui-audit-button-native.log` 的 JNI 清单、无 TTS、STQ 来源及 ELF / ZIP 16 KB 门禁通过。曾因上下外距都加至 8 dp 触发阅读高度回归，已调整布局，未降低断言。最新 APK 模拟器按下 / 暂停 / 播放 / 320 dp 200% 字体证据在 `.tools/ui-audit/after/buttons/`。独立专项评分与审查器额度限制记录见 27_UI_AUDIT.md；不覆盖原全流程待验收状态。

将所有者提供的 94 项检查分配到七个实际 UI 环节，确实不存在的卡片网格、表格和可拖拽浮动工具栏记为不适用，环境尚未验证的功能不移出清单。主线程统一公共间距、图标与点击目标，修复大字体主题按钮高度和字幕元数据分组、窄屏弹窗外距与滚动、危险确认配色、整行演示开关、城市保存反馈、空态说明及无报告禁用说明。已有默认衬线字体工作保留。逐项与独立 Luna Max 评分见 [27_UI_AUDIT.md](27_UI_AUDIT.md)，尚未达到连续两次全部满分，不宣告通过。

最终 `scripts/build.ps1 -Tasks @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest', ':app:assembleHyValidation', '--no-daemon', '--max-workers=2')` 成功，1m 38s / 123 tasks；21 suites / 220 tests，0 failures / errors，其中 ComposeUiTest 59 tests。lint 0 errors / 16 warnings。日志 `.tools/ui-audit-validation-final4.log`；`scripts/verify-native.ps1` 对最终 Debug APK 的必需 JNI、无 TTS、固定 STQ 来源清单及 ELF / APK ZIP 16 KB 对齐全部通过，日志 `.tools/ui-audit-native-final.log`。Android 测试 APK 仅编译，未执行 connected instrumentation。

本轮启动 API 35 x86_64 Android 模拟器，以独立 `.hyvalidation` 包验证系统权限拒绝、语言菜单键盘操作、320 dp / 200% 字体及 IME 弹窗、SAF 导入四模型、模型详情 / 删除 / 重导入、演示记录与 TXT / SRT / JSON 实际保存、删除确认取消和两次独立左滑、手动城市保存及冷启动保留。截图和 XML 在 `.tools/ui-audit/after/`，导出内容为明确标记的演示字幕。原有主包因签名不兼容没有覆盖或卸载。模拟器显示参数已恢复为本轮开始时的 1080×2520 / 480 dpi / fontScale 1.0。

验证边界：x86_64 模拟器无法加载生产 ARM64 原生库，实际拾音、暂停及由真实拾音产生的诊断导出未验收；模型下载实际进入失败状态，未验收真实传输取消；合成坐标城市查询进入失败回退，Geocoder 成功未验收；OEM 折叠 / 多窗口 / 真实 TalkBack 与真人模型质量仍缺物理设备证据。这些保留为适用但待验证，不能靠提高分数关闭。未提交或推送。
