# Chernika AI v0.3 — Free Hybrid, with Qwen3 on-device

## What is implemented
- Free cloud backend through Cloudflare Worker + OpenRouter's free model router
  (the previous online setup continues to work; still needs user secrets).
- True offline text generation through Google's LiteRT-LM Kotlin API, running
  Qwen3-0.6B on CPU. Offline inference does not send your text to a provider.
- In-app **Model** button: download model from Hugging Face or import a local
  .litertlm model using Android's file picker.
- AUTO: cloud if configured/reachable; real offline model as fallback.
- OFFLINE: real model when installed; clear preview otherwise.
- ONLINE: cloud only, with provider errors surfaced.
- Device-local chat transcript, and encrypted gateway token.

## Download Qwen3 0.6B (~586 MB)
Inside Chernika AI tap **Model → Download Qwen3**. Allow ~700 MB of free
storage; Wi-Fi recommended. The app downloads directly from:
https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm

If Hugging Face is restricted, obtain the same file via another allowed
device/network and choose **Model → Import .litertlm file**. It must be the
Qwen3-0.6B.litertlm model (int8), not a GGUF or newer int4 variant.
The downloaded file is not bundled in APK due to its size.
The model comes from litert-community with Apache 2.0 licensing metadata.

## Device caveats
- Only arm64 Android is packaged in this experimental APK.
- LiteRT-LM uses CPU initially; slow generation and substantial RAM use
  are possible. The app's build completing is NOT proof that your device
  can run the model successfully. Test it on a real device.
- The offline model has its own in-process conversation state. Prior
  messages shown from app storage are not automatically reloaded into
  that context after restart or after cloud usage.
- Offline model must be imported/downloaded once. Once stored, chat in
  OFFLINE mode works without network.
- No microphone, vision, stream cancellation, or GPU/NPU optimization yet.

## Free cloud setup
See assistant-android/backend/worker.js and the previous guide.
Set secret OPENROUTER_API_KEY and CHERNIKA_ACCESS_TOKEN in Cloudflare Workers
Free. Never enter API keys in the Android app or commit to GitHub.

## Build
Workflow: Chernika AI Android preview APK in GitHub Actions on this branch.
Runs on GitHub infrastructure, so Android Studio is not needed locally.
