# Chernika AI — Free Hybrid Android (stage 0.3)

This is an **independent Android assistant** under assistant-android/.
The separate Plato 9-ball engine elsewhere in this repository remains unchanged.

## Free-tier route (no paid OpenAI API)

You can keep using the existing Chernika Android v0.2 APK. **Only the
Cloudflare Worker code changes**. The existing Android app already supports
the \`POST /chat\` JSON contract, private gateway token, and HTTPS URL.

- Cloud AI: OpenRouter \`openrouter/free\` using an OpenRouter API key.
- Backend: Cloudflare Workers Free account.
- On-phone chat history: no cloud database required.
- OFFLINE mode: **rules-based preview only**, not an offline neural model yet.
- AUTO mode: free cloud first; limited offline preview on network failure.
- Real on-device language model is a separate future implementation.

OpenRouter's no-purchased-credits free-model limit was 50 requests/day and
20 requests/minute on November 20, 2026. Availability and quotas may change.
Cloudflare Workers Free provides a generous request quota, currently 100k/day.
No payment is needed for these *free tiers*, subject to account eligibility.
Free providers may log/process requests; avoid sending highly sensitive data.

## Set up from a mobile browser (no Android Studio, no local SDK)

1. Go to https://openrouter.ai/ and create a FREE account.
2. Go to https://openrouter.ai/settings/keys and create an API key.
   You do not need to purchase credits to use free variants. Never share the key.
3. Go to https://dash.cloudflare.com/ -> Workers & Pages -> Create Worker.
   Select the **Workers Free** plan.
4. Open the Worker code editor, and paste the entire text from
   https://github.com/mohammaparsaj-ui/Chernika/blob/feature/chernika-ai-android-v0/assistant-android/backend/worker.js
5. Save and Deploy the Worker.
6. In the Worker's Settings -> Variables and Secrets, add TWO secrets:
   - OPENROUTER_API_KEY : your private OpenRouter API key
   - CHERNIKA_ACCESS_TOKEN : a second, long random password *you* choose
   Never paste either into GitHub or ChatGPT.
7. Deploy the updated settings.
8. Visit https://YOUR-WORKER.workers.dev/health and confirm status=ok.
9. Open the existing Chernika AI Android v0.2 app -> Settings.
   Enter the Worker's HTTPS URL without /chat, and enter the *second*
   CHERNIKA_ACCESS_TOKEN (not the OpenRouter key).
10. Select ONLINE and ask: "Chernika, tell me about yourself."
    If you receive a dynamic intelligent answer, the free AI is connected.

## Optional Groq Free alternate provider (if OpenRouter is unavailable)

1. Sign up for GroqCloud Free at https://console.groq.com/keys.
2. Add GROQ_API_KEY as a Worker secret.
3. Add AI_PROVIDER as a **text** variable with value groq.
4. Save/redeploy. The Worker then uses the Groq-hosted
   openai/gpt-oss-20b model under Groq's Free limits, **not** OpenAI billing.
5. To return to OpenRouter, set AI_PROVIDER=openrouter, save/redeploy.
   Only use the free-tier plan; do not upgrade to a paid tier.

## Safety and limitations

- This is a **single-user prototype**, not an audited production service.
  A bearer token alone lacks sophisticated rate limiting. Use a long random
  access token, keep it private, and rotate it if compromised.
- The OpenRouter key is a general credential: we deliberately force
  model=openrouter/free on the server and never accept model names from the
  phone. Avoid adding paid models to the backend.
- When ONLINE is selected, conversation messages go to Cloudflare, OpenRouter
  (or Groq), and the chosen model provider. OFFLINE preview stays on-device.
- The APK does not store any provider API keys. It encrypts only the separate
  gateway token with Android Keystore.
- If the free model reaches its quota, ONLINE shows a clear rate-limit error.
  AUTO falls back to local preview; this is not full offline reasoning.
- Free sign-up and access may vary by region.

## Check the build

GitHub Actions workflow "Chernika AI Android preview APK" continues to build
the existing app. No new APK is required just for the new backend provider.
