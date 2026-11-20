import test from "node:test";
import assert from "node:assert/strict";
import worker from "./worker.js";

const url = "https://chernika-example.workers.dev";
const token = "test-only-chernika-access-token";
const env = { CHERNIKA_ACCESS_TOKEN: token, OPENROUTER_API_KEY: "test-only-openrouter-key" };

function request(path, body, bearer = token) {
  return new Request(url + path, {
    method: "POST",
    headers: { Authorization: "Bearer " + bearer, "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });
}

test("free worker offers a public health endpoint without keys", async () => {
  const response = await worker.fetch(new Request(url + "/health"), {});
  assert.equal(response.status, 200);
  assert.equal((await response.json()).cloud, "free-tier");
});

test("chat rejects missing or incorrect access token before upstream", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = () => { throw new Error("No upstream should be called"); };
  try {
    const result = await worker.fetch(request("/chat", { message: "hi" }, "wrong"), env);
    assert.equal(result.status, 401);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("default provider is strictly openrouter/free, never paid", async () => {
  const originalFetch = globalThis.fetch;
  let upstreamUrl = "", payload = null, upstreamAuth = "";
  globalThis.fetch = async (url, options) => {
    upstreamUrl = url;
    payload = JSON.parse(options.body);
    upstreamAuth = options.headers.Authorization;
    return Response.json({ choices: [{ message: { content: "Hello, I'm Chernika." } }] });
  };
  try {
    const result = await worker.fetch(request("/chat", {
      message: "Who are you?",
      history: [{ role: "user", content: "Hi" }]
    }), env);
    assert.equal(result.status, 200);
    assert.equal((await result.json()).reply, "Hello, I'm Chernika.");
    assert.equal(upstreamUrl, "https://openrouter.ai/api/v1/chat/completions");
    assert.equal(payload.model, "openrouter/free");
    assert.equal(upstreamAuth, "Bearer test-only-openrouter-key");
    assert.equal(payload.messages[0].role, "system");
    assert.equal(payload.messages.at(-1).content, "Who are you?");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("optional Groq routing also uses a configured free-tier model", async () => {
  const originalFetch = globalThis.fetch;
  let upstreamUrl = "", model = "";
  globalThis.fetch = async (url, options) => {
    upstreamUrl = url;
    model = JSON.parse(options.body).model;
    return Response.json({ choices: [{ message: { content: "Groq response" } }] });
  };
  try {
    const result = await worker.fetch(
      request("/chat", { message: "Hello" }),
      { CHERNIKA_ACCESS_TOKEN: token, AI_PROVIDER: "groq", GROQ_API_KEY: "test-key" }
    );
    assert.equal(result.status, 200);
    assert.equal(upstreamUrl, "https://api.groq.com/openai/v1/chat/completions");
    assert.equal(model, "openai/gpt-oss-20b");
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("free provider rate limit produces a clear error without fallback billing", async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => new Response("Too Many Requests", { status: 429 });
  try {
    const result = await worker.fetch(request("/chat", { message: "Hi" }), env);
    assert.equal(result.status, 429);
    const parsed = await result.json();
    assert.match(parsed.error, /rate limit/i);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("oversize requests are rejected before reaching any provider", async () => {
  const result = await worker.fetch(request("/chat", { message: "a".repeat(2501) }), env);
  assert.equal(result.status, 400);
});
