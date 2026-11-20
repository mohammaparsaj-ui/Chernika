/**
 * Chernika AI — $0 cloud gateway (Cloudflare Workers Free + OpenRouter free models)
 *
 * Secrets in Worker Settings → Variables and Secrets (never inside Android):
 *   OPENROUTER_API_KEY       OpenRouter API key on a free account
 *   CHERNIKA_ACCESS_TOKEN   long, random, private token chosen by the app owner
 *
 * This server intentionally requests ONLY the free router model:
 *   openrouter/free
 * It has no configured path to a paid model.
 *
 * Optional future provider, independently free-tier limited:
 *   AI_PROVIDER=groq  (text variable)
 *   GROQ_API_KEY     (secret)
 * Groq defaults to openai/gpt-oss-20b; this is a Groq-hosted open-weight model,
 * not OpenAI's paid API.
 */

const persona = [
  "You are Chernika AI, a capable personal assistant.",
  "Style: warm, curious, helpful, a little playful, clear and concise.",
  "Respond in the user's language. Answer accurately, acknowledge uncertainty.",
  "Do not pretend to be human or claim to have tools, browsing, or memories",
  "you do not actually have. You see only the conversation passed to you.",
  "Do not request sensitive credentials from the user."
].join(" ");

function output(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      "x-content-type-options": "nosniff"
    }
  });
}

function aiConfig(env) {
  const provider = env.AI_PROVIDER === "groq" ? "groq" : "openrouter";
  if (provider === "groq") {
    return {
      url: "https://api.groq.com/openai/v1/chat/completions",
      model: "openai/gpt-oss-20b",
      apiKey: env.GROQ_API_KEY
    };
  }
  return {
    url: "https://openrouter.ai/api/v1/chat/completions",
    model: "openrouter/free",
    apiKey: env.OPENROUTER_API_KEY
  };
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === "GET" && path === "/health") {
      return output({
        status: "ok",
        app: "Chernika AI",
        version: "0.3-free",
        cloud: "free-tier"
      });
    }
    if (request.method !== "POST" || path !== "/chat") {
      return output({ error: "Not found" }, 404);
    }
    if (!env.CHERNIKA_ACCESS_TOKEN) {
      return output({ error: "Server access token not configured." }, 503);
    }
    if (request.headers.get("Authorization") !==
        "Bearer " + env.CHERNIKA_ACCESS_TOKEN) {
      return output({ error: "Unauthorized: check the Chernika access token." }, 401);
    }

    const provider = aiConfig(env);
    if (!provider.apiKey) {
      return output({
        error: provider.model === "openrouter/free"
          ? "Configure OPENROUTER_API_KEY in Worker secrets."
          : "Configure GROQ_API_KEY in Worker secrets."
      }, 503);
    }

    let rawBody;
    try {
      // Bound request size even when Content-Length is missing or incorrect.
      rawBody = await request.text();
      if (rawBody.length > 18000) {
        return output({ error: "Request is too large." }, 413);
      }
    } catch (_) {
      return output({ error: "Could not read request." }, 400);
    }

    let body;
    try {
      body = JSON.parse(rawBody);
    } catch (_) {
      return output({ error: "Invalid JSON." }, 400);
    }
    if (!body || typeof body.message !== "string" ||
        !body.message.trim() || body.message.length > 2500) {
      return output({ error: "Message must contain 1–2500 characters." }, 400);
    }

    const past = Array.isArray(body.history) ? body.history.slice(-12) : [];
    const messages = [{ role: "system", content: persona }];
    for (const item of past) {
      if (!item || !["user", "assistant"].includes(item.role) ||
          typeof item.content !== "string") continue;
      const content = item.content.trim().slice(0, 2500);
      if (content) messages.push({ role: item.role, content });
    }
    messages.push({ role: "user", content: body.message.trim() });

    let upstream;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 35000);
    try {
      upstream = await fetch(provider.url, {
        method: "POST",
        signal: controller.signal,
        headers: {
          "Content-Type": "application/json",
          "Authorization": "Bearer " + provider.apiKey,
          ...(provider.model === "openrouter/free"
            ? { "HTTP-Referer": "https://github.com/mohammaparsaj-ui/Chernika",
                "X-Title": "Chernika AI" }
            : {})
        },
        body: JSON.stringify({
          model: provider.model,
          messages,
          max_tokens: 650,
          temperature: 0.65,
          stream: false
        })
      });
    } catch (_) {
      return output({ error: "Free AI provider is unreachable; try again." }, 502);
    } finally {
      clearTimeout(timeout);
    }

    if (!upstream.ok) {
      if (upstream.status === 429) {
        return output({
          error: "Free model rate limit reached. Retry after the provider resets."
        }, 429);
      }
      if (upstream.status === 401 || upstream.status === 403) {
        return output({
          error: "Free AI API key rejected. Check the Worker secret."
        }, 502);
      }
      return output({
        error: "Free AI model is currently unavailable. Try again later."
      }, 502);
    }

    let result;
    try {
      result = await upstream.json();
    } catch (_) {
      return output({ error: "Invalid response from free AI provider." }, 502);
    }

    const message = result?.choices?.[0]?.message;
    let reply = message?.content;
    if (Array.isArray(reply)) {
      reply = reply.filter(x => x && x.type === "text")
        .map(x => x.text || "").join("\n");
    }
    if (typeof reply !== "string" || !reply.trim()) {
      return output({ error: "The free model returned an empty reply." }, 502);
    }
    return output({ reply: reply.trim(), brain: "free-cloud" });
  }
};
