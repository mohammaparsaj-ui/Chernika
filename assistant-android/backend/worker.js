// Chernika AI private Cloudflare Worker. No API keys in source code.
const instructions = "You are Chernika AI, an intelligent personal assistant. " +
  "Be helpful, accurate, clear, concise and friendly, with a playful touch. " +
  "Adapt to the user's language. Don't claim abilities or memories you don't have.";
const json = (obj, status = 200) => new Response(JSON.stringify(obj), {
  status,
  headers: {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "x-content-type-options": "nosniff"
  }
});

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === "GET" && path === "/health") {
      return json({ status: "ok", app: "Chernika AI", version: "0.2" });
    }
    if (request.method !== "POST" || path !== "/chat") {
      return json({ error: "Not found" }, 404);
    }
    if (!env.OPENAI_API_KEY || !env.CHERNIKA_ACCESS_TOKEN) {
      return json({ error: "Configure Worker secrets first." }, 503);
    }
    if (request.headers.get("Authorization") !==
        "Bearer " + env.CHERNIKA_ACCESS_TOKEN) {
      return json({ error: "Unauthorized: check your access token." }, 401);
    }
    if (Number(request.headers.get("content-length") || 0) > 16000) {
      return json({ error: "Message too large." }, 413);
    }
    let body;
    try { body = await request.json(); }
    catch (_) { return json({ error: "Invalid JSON." }, 400); }
    if (typeof body?.message !== "string" ||
        !body.message.trim() || body.message.length > 2500) {
      return json({ error: "Message must be 1-2500 characters." }, 400);
    }
    const past = Array.isArray(body.history) ? body.history.slice(-12) : [];
    const input = past.flatMap(item => {
      if (!item || !["user", "assistant"].includes(item.role) ||
          typeof item.content !== "string") return [];
      return [{ role: item.role, content: item.content.slice(0, 2500) }];
    });
    input.push({ role: "user", content: body.message });
    let provider;
    try {
      provider = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + env.OPENAI_API_KEY
        },
        body: JSON.stringify({
          model: env.OPENAI_MODEL || "gpt-4.1-mini",
          instructions,
          input,
          max_output_tokens: 650,
          store: false
        }),
        signal: AbortSignal.timeout(35000)
      });
    } catch (_) {
      return json({ error: "Could not reach the AI provider." }, 502);
    }
    if (!provider.ok) {
      return json({
        error: provider.status === 429
          ? "AI usage limit reached."
          : "AI provider error: verify API key, credits and model."
      }, provider.status === 429 ? 429 : 502);
    }
    let result;
    try { result = await provider.json(); }
    catch (_) { return json({ error: "Invalid AI response." }, 502); }
    const reply = (result.output || [])
      .flatMap(item => item.content || [])
      .filter(item => item.type === "output_text" && typeof item.text === "string")
      .map(item => item.text).join("\n").trim();
    return reply ? json({ reply, brain: "cloud" })
      : json({ error: "AI returned no text." }, 502);
  }
};
