---
fork: https://github.com/dmitrcher/Operit
---

# 本地俄语 / 多语言语音识别

## 原状况

- 本地 STT（`SHERPA_NCNN`）只有 sherpa-ncnn 中英双语 zipformer，不能识别俄语。
- 悬浮窗语音、唤醒监听都把识别语言写死为 `zh-CN`，云端 STT 也因此收不到用户语言。
- VAD 在 300ms 静音后就结束短语，且语音起点前的帧被丢弃，句中停顿会把话截断。

## 改动

- 新增三个本地 STT 引擎（`SpeechServiceType`），都由 `SherpaOnnxOfflineSpeechProvider` 实现：
  - `SHERPA_ONNX_PARAKEET_V3`：NVIDIA Parakeet TDT 0.6B v3 int8，25 种欧洲语言（含俄语、英语），自动识别语种，带标点和大小写，CC-BY-4.0。
  - `SHERPA_ONNX_FASTCONFORMER`：NVIDIA FastConformer 多语言 transducer int8，10 种语言（含俄语、英语），带标点，CC-BY-4.0。
  - `SHERPA_ONNX_GIGAAM_V3`：Sber GigaAM v3 带标点 transducer，俄语，MIT。
- 这些是非流式模型：Silero VAD 切分短语，短语结束后整段解码为最终结果；说话中每秒重解码一次作为中间结果。
- 三个模型不打包进 APK：`SherpaOnnxSttModel` 记录来源、大小和 SHA-256，`SttModelDownloadManager` 在运行时下载到
  `files/.stt_onnx_models`（应用更新后保留，不进入数据快照），支持断点续传；FastConformer 只有 release 压缩包，下载校验后解压。
- 语音服务设置的 STT 页在选择这些引擎时显示模型卡片：下载 / 取消 / 删除与进度。
- `syncSherpaOnnxAar` 下载并校验 `sherpa-onnx-static-link-onnxruntime-2.0.1.aar`；onnxruntime 静态链接，不与 `onnxruntime-android` 冲突。
- VAD：静音 800ms 才结束短语，并保留语音起点前 ~320ms 音频；`SherpaSpeechProvider` 同样修正。
- `LocaleUtils.getSpeechLanguageTag` 让悬浮窗语音、唤醒监听、语音识别工具默认跟随应用语言。
- OpenAI / Deepgram STT 的语言映射与支持列表加入 `ru`。

## 已知限制

- 模型需在设置中手动下载：Parakeet 约 670 MB，FastConformer 约 107 MB，GigaAM 约 232 MB。
- 非流式模型的中间结果每秒刷新一次，不是逐字出现。
- GigaAM v3 只识别俄语；sherpa-ncnn 引擎只支持中文、英文。

[DONE]
