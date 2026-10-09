# ADR-0001 — 采用 Android Native Kotlin/Compose

日期：2026-10-09；状态：Accepted。

## 背景

产品优先 Android 单平台、连续 AudioRecord、系统权限、前台录音、ONNX/JNI 与较强的离线性能约束。跨端方案不会消除原生层实现，并额外带来桥接与双状态系统。

## 决策

采用 Kotlin、Jetpack Compose、Material 3、Navigation Compose、ViewModel + StateFlow；主 UI 不使用 Flutter、React Native 或 WebView。音频采集和模型推理封装在独立层，Composable 不拥有音频会话生命周期。

## 后果

获得直接的 Android API、主题、无障碍与后台生命周期集成；代价是不复用 iOS GUI。若未来支持 iOS，优先重用模型/事件协议与算法设计，而不是倒逼当前 App 改造为跨端框架。

## 复核时间点

首次 Android 可运行骨架后，检查 Compose 字幕更新性能、系统字体缩放和分屏适配。
