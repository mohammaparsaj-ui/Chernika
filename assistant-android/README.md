# Chernika AI v0.2 — Android with secure online AI

This is a separate Android assistant in assistant-android/. The 9-ball game
engine remains untouched.

## What works
- Android chat interface and private on-device conversation history.
- AUTO, ONLINE, and OFFLINE mode selectors.
- Rules-based offline preview with /help, /remember NOTE, /memory, /forget.
- An optional real online model through a private HTTPS Cloudflare Worker.
- Securely encrypted Chernika access token stored using Android Keystore.

## What does not work yet
- OFFLINE is NOT a language model, only a deterministic preview.
- Actual offline neural model inference, voice, and image understanding are future stages.
- Online inference requires your own backend and OpenAI API billing.

## Configure Cloudflare Worker from your browser

1. Get an OpenAI API key from https://platform.openai.com/api-keys.
   API billing is separate from a ChatGPT subscription.
2. Open https://dash.cloudflare.com/ and create a Worker under Workers & Pages.
3. Edit its source and paste in assistant-android/backend/worker.js from GitHub.
4. In Worker Settings > Variables and Secrets, create these TWO **secrets**:
   OPENAI_API_KEY: your OpenAI API key.
   CHERNIKA_ACCESS_TOKEN: a long random password you choose yourself.
   Optionally add text variable OPENAI_MODEL = gpt-4.1-mini.
5. Save and deploy the Worker.
6. Visit https://YOUR-WORKER.workers.dev/health. It should return a JSON
   response containing status = ok. This tests availability, not API credits.
7. Open the Android app, tap Settings, enter the base HTTPS Worker address
   without /chat, and type your CHERNIKA_ACCESS_TOKEN. Do NOT enter
   OPENAI_API_KEY in the app.
8. Select ONLINE and ask a question. AUTO tries cloud first and falls back
   to the local preview if the server is unreachable.

WARNING: This is for a single user and personal testing. It is not a
production authentication service. Use a long unique token, protect the
Worker URL and token, set OpenAI spending controls, and monitor usage.
Your messages are sent to Cloudflare and OpenAI when ONLINE is active.
Do not place any secrets in source control or send them into this chat.

## Build using GitHub Actions (no Android Studio required)

On GitHub open the Actions tab, select "Chernika AI Android preview APK",
open the latest successful run and download the artifact
"Chernika-AI-Preview-APK". Extract the ZIP, then install app-debug.apk.
