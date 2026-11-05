# LinDo 零度娘矢量资产

本轮最终方案是透明底路径 SVG，保留棕发、狐耳、金瞳、冕冠与黑金服饰。图形不包含截图、文字贴纸、背景板或内嵌 bitmap。

- `lindo-mark.svg`：应用图标与品牌字标的头像。
- `lingdu-welcome.svg`：登录欢迎页、空状态和完成状态。
- `lingdu-connecting.svg`：加载、刷新和网络异常。
- `sources/`：内置 imagegen 工具生成的透明 PNG 原稿，仅为设计溯源，不进入 App 资源包。人物参考由用户提供；不代表 LINUX DO 官方设计或背书。

## 使用与导出

修改规范 SVG 后运行：

```sh
python3 scripts/generate-brand-vectors.py
```

脚本仅使用标准库，导出 Compose/Android VectorDrawable 和 adaptive icon 前景。源文件须是扁平 path 或 translate path。每条路径必须低于 30 KB，避免 AAPT 静默写入 STRING_TOO_LARGE。当前矢量经过 vtracer 0.6.15 转换、SVGO 4.1 精度 1 优化，并人工清理欢迎图左侧独立噪点。源 SVG 为后续设计编辑的规范文件。

旧系统 launcher PNG 是最终 SVG 的栅格导出，供 Android 7 等不支持 adaptive icon 的系统使用；主站欢迎图与状态图使用矢量。修改 logo 后也需重导出各 mipmap 密度的方形及圆形图标。

## 动效

`LinDoMascot.kt` 使用 Compose graphicsLayer 对静态矢量做 ±1.8° 轻摇摆。位移不触发布局；网络错误和完成状态保持静止。遵循应用动效开关、系统关闭动画与窗口焦点；没有 Lottie/Compottie 依赖。六种语义状态通过姿态、文案与动作强度表达，共用两张精细插画，不为每种状态重新绘制粗糙角色。

## 生成溯源

工具：会话内置 imagegen。欢迎图与连接图基于用户提供的零度娘形象生成透明独立插画，经过视觉核对与矢量化；图标在精细头像稿基础上移除金色底板，保留人物特征。

图标最后一次编辑的完整提示词见 `sources/mark-prompt.txt`。欢迎/连接原始调用提示词未在工作区留存，不能保证从文字重生成完全相同的原稿；已保留实际原稿与最终 SVG，可直接重导出。
