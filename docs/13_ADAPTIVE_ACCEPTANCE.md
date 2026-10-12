# 折叠屏适配质量验收

用户目标：适配成熟度接近小红书。验收重点为展开后的空间利用、双栏协同、内外屏连续性、触控与阅读效率，而非仅拉伸手机页面。当前没有直接验收小红书各 OEM / 版本行为，也未达到可宣称同等成熟度的真机证据。

## 已实现

- 平屏展开按实际正文宽度分栏，不依赖机型白名单或厂商强制分屏；铰链分隔 / 实体遮挡优先，窄区和大字体保守回退。
- Live 历史与最新译文 / 控制并排；tabletop 保持上方字幕 / 下方控制。录音资源仍由 Service 持有。
- Models 语音模型 / 翻译模型分区，Settings 显示与隐私 / 性能与诊断分区，单栏时按内容顺序滚动。
- Sessions 列表 / 详情并排，选中态有语义标签，收窄保留详情和阅读位置；紧凑详情的系统返回先回列表。
- 正文按完整可用区域裁切，独立滚动；重命名 / 导出弹窗和工具提示延续铰链避让。

## 证据矩阵

| 情况 | 当前证据 | 仍须证明 |
| --- | --- | --- |
| 外屏 / 紧凑手机 | 360 / 412 dp 主机 UI，控制与导航渲染 | OEM 外屏、真实系统栏与手势 |
| 900 dp 平屏展开 | Live / Settings 双栏几何断言及实际组件截图；Models / Sessions 共用布局 | OEM 展开、动态尺寸及滑动手感 |
| book / FULL 铰链 | 四页 UI 与模型 / 设置 / 记录 / 控制分区检查 | OEM 精确铰链、真实触控热区 |
| tabletop | 四页主机 UI；模型导入两区入口、记录选中 / 滚动 / 导出均有几何断言与截图 | OEM 真机悬停、触控和实际 SAF |
| 多铰链、窄区域 | 主机纯几何与单区回退 | 多区实际组件及三折设备 |
| 收窄后阅读连续 | Record 2 Line 5 跨 900→412→900 dp app viewport 保留；选中项保留 | Android 窗口重建与真实内外屏切换 |
| 保存状态恢复 | StateRestorationTester 恢复选择及第 15 行的精确位置，包括延迟载入；ActivityScenario 重建生产 MainActivity 后导航 / 选择 / 第 15 行位置与返回流程通过 | 设备窗口重建、真实进程回收后的恢复 |
| 200% 字体 | book 设置隐私 / 性能互不覆盖、独立滚动；手机停止可达截图 | 全页长内容、OEM 最大字体 / 显示缩放 |
| 持续录音开合 | 合成 RecordingState 下姿态变化不重复发送命令；Service 所有权静态检查 | AudioRecord / JNI、锁屏 / 后台 / 开合连续性 |
| 键盘、弹窗与输入 | 主机弹窗语义 / 几何及独立窗口截图，生产采用 imePadding | 真实 IME、焦点 / Tab、鼠标与分屏调整 |
| 流畅度 | 静态 native graphics 组件渲染 | 开合动画、实际帧率、卡顿与系统动画减少 |

折叠功能双栏阶段为 128 项主机测试通过；首个 prealpha 的最终普通回归为 142 项，当前发行证据见 11_VALIDATION。主机截图为 Android Canvas / Compose 实际绘制，字幕为合成数据；不是设计稿，也不是设备 PixelCopy。窗口收窄测试改变 app viewport，不是折叠设备。报告没有真实录音或模型性能数字。

prealpha.2 增加 4 项回归，普通主机总数 146。发现并修复等待记录载入时滚动 key 变为 null 导致恢复位置丢失；最初第 5 行可见断言不足，改为第 15 行 / 1 px 坐标后旧实现失败，修复后通过。新增 ActivityLifecycleUiTest 使用生产 MainActivity 和 Room 文本记录，确认 Activity 实例确实被替换，不仅重新组合页面；它在 Robolectric API 35 运行，不是真机，也不是操作系统杀进程 / 冷启动。来源：[ActivityScenario.recreate](https://developer.android.com/reference/androidx/test/core/app/ActivityScenario#recreate())。

整体完成仍必须满足 01_REQUIREMENTS 的 R1–R8 和 08_ROADMAP 的设备 / 离线 / 准确率 / 热机 / 合规门槛。此矩阵不缩小原目标，不用主机通过替代设备验收。

参考：[Android adaptive apps](https://developer.android.com/develop/adaptive-apps/guides/get-started-with-adaptive-apps)、[列表详情模式](https://developer.android.com/develop/adaptive-apps/guides/list-detail)。
