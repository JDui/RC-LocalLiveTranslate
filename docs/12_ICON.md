# 翻译魔芋应用图标

2026-10-09，使用内置 `image_gen.imagegen` 工具生成原创透明 raster 前景，未采用 CLI、漫画截图或现成图标。选择的原图复制到 `app/src/main/res/drawable-nodpi/translation_konjac.png`，保留原始生成文件，不对 PNG 进行后处理。

图形为柔和半透明的魔芋块、细小矿物斑点及中部浅槽，配玉绿 / 珊瑚色双向对话标记。无文字和角色，优先小尺寸辨认。Android 自适应层使用 6 dp inset 和 `#183D38` 底色；API 33+ 增加独立矢量 monochrome 轮廓。Manifest 的 icon / roundIcon 指向 `@mipmap/ic_launcher`；录音通知仍使用 `@drawable/ic_app`，避免彩色 raster 变成不清晰的通知蒙版。

最低 API 28 已支持 adaptive icon，资源放在 `mipmap-anydpi`，三个图层统一登记，旧系统忽略 monochrome。设计依据：[Android adaptive icon 规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

主机 LauncherIconTest 检查 manifest 入口、透明背景、主题层、API 28 的 48 / 192 px 圆形裁切、内容像素和细节，并输出 `app/build/ui-screenshots/launcher-konjac.png`。这不是 OEM launcher、实际动态视差或 themed icon 的设备验收。

## 生成提示

```text
Create an ORIGINAL polished native Android app launcher foreground icon inspired by the concept of a translation konjac jelly, not copying an existing illustration. Transparent background, square 1024 x 1024 canvas. Only one centered emblem, occupying approximately 56 percent of canvas width and height, with generous completely transparent padding for Android adaptive icon safe-zone masking. A pale pearl-gray gently translucent rectangular konjac jelly block with rounded soft corners, tiny tasteful dark mineral flecks, and one subtle central horizontal groove, viewed three-quarter from slightly above. Soft sculptural liquid-glass edges, restrained glossy highlights, clearly readable silhouette at 48 pixels. Add two tiny integrated speech-bubble accents on the jelly, one jade green and one coral pink, conveying translation between languages through opposed directions without any words or letters. Sophisticated fresh jade/coral/pearl palette. No character, no face, no text, no letters, no watermark, no frame, no circular/squircle background, no drop shadow outside the emblem, no scene. The whole jelly and speech accents must fit inside the central safe zone. Raster 3D icon render, sharply defined translucent object rather than blurry glass.
```

工具参数 `transparent_background=true`。最终实际分辨率以资产及测试为准，不把提示中的尺寸当作生成结果保证。发行前仍需审查题材相关权利；没有因此为本仓库指定许可证。
