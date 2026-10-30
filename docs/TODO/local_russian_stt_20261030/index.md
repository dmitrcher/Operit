---
fork: https://github.com/dmitrcher/Operit
---

# 本地俄语语音识别

## 原状况

- 本地 STT（`SHERPA_NCNN`）只有 sherpa-ncnn 中英双语 zipformer，不能识别俄语。
- 悬浮窗语音、唤醒监听都把识别语言写死为 `zh-CN`，云端 STT 也因此收不到用户语言。

## 改动

- 构建时按 `app/config/stt-model-assets.properties` 额外获取 sherpa-onnx T-one 俄语流式 CTC 模型
  （`csukuangfj/sherpa-onnx-streaming-t-one-russian-2025-09-08`，Apache-2.0）。
- `syncSherpaOnnxAar` 下载并校验 `sherpa-onnx-static-link-onnxruntime-2.0.1.aar`；该 AAR 把 onnxruntime
  静态链接进 `libsherpa-onnx-jni.so`，与应用已有的 `onnxruntime-android` 不冲突。
- `SherpaSpeechProvider` 按 `startRecognition(languageCode)` 选择模型：`zh`/`en` 使用 sherpa-ncnn，
  `ru` 使用 sherpa-onnx T-one；其他语言返回明确的不支持错误。
- `LocaleUtils.getSpeechLanguageTag` 让悬浮窗语音、唤醒监听、语音识别工具默认跟随应用语言
  （`zh-CN` / `en-US` / `ru-RU`）。
- OpenAI / Deepgram STT 的语言映射与支持列表加入 `ru`。

## 已知限制

- T-one 只识别俄语，输出小写、无标点；同一句话混说俄语和英语时，英文单词会被按俄语转写。
- 唤醒词需要与当前识别语言一致（应用语言为俄语时应设置俄语唤醒词）。

[DONE]
