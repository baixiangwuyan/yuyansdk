# 第三方音效来源

`src/main/res/raw/cyime_key_*.wav`（花花「按键音色 → CyIME 风格」）来自开源输入法 **CyIME**：

- 仓库：https://github.com/Cyletix/CyIME （tag 1.15.94，提交 6f97a55）
- 许可：GNU GPL v3.0（与本项目相同）。CyIME 的 TRADEMARKS.md 只保留名称、Logo、图标和包名，不含音效。
- 原文件 → 本仓库文件：
  - `app/src/main/res/raw/kb_rec_normal.wav` → `cyime_key_normal.wav`
  - `app/src/main/res/raw/kb_rec_backspace.wav` → `cyime_key_delete.wav`
  - `app/src/main/res/raw/kb_rec_space.wav` → `cyime_key_space.wav`
  - `app/src/main/res/raw/kb_rec_enter.wav` → `cyime_key_enter.wav`
- 内容：CyIME 称为「Rain75 紫薇轴」的机械键盘实录，是 CyIME 软键盘的默认音色。
- 修改：每个文件切掉开头 4 ms（176 个采样，振幅不到峰值 3% 的静音段），并加 1 ms 淡入，让声音更早出来；其余未改（44.1 kHz 单声道 16 位 PCM）。
