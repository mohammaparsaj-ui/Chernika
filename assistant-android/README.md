# Chernika AI — Android stage 1

This is an **independent assistant app** located under `assistant-android/`.
It is separate from the Chernika Plato 9-ball engine in this repository.

## Working in this prototype
- Native Android text chat UI in a purple theme
- AUTO, OFFLINE and ONLINE mode selectors
- Simple local deterministic preview replies
- On-device conversation persistence and a clear-history control

## Not yet implemented
- Real on-device language model (offline LLM)
- Secure cloud backend + OpenAI model
- Automatic intelligent switching
- Voice, vision, full long-term semantic memory

These are intentionally left for later stages: do not describe this preview as full AI.

## Build without Android Studio
Open GitHub Actions → **Chernika AI Android preview APK** → latest successful run →
**Chernika-AI-Preview-APK** under Artifacts. Download and extract the ZIP,
then install `app-debug.apk` on your phone.

Build locally inside the `assistant-android` directory with Gradle 9.8.2 and a
compatible Android SDK: `gradle :app:assembleDebug`.
