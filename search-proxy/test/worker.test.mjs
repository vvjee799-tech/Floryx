import assert from "node:assert/strict";
import test from "node:test";
import { handleRequest } from "../src/index.js";

const allowedLimiter = { limit: async () => ({ success: true }) };

test("forwards a validated query and returns sanitized Tavily results", async () => {
  let upstreamAuthorization;
  const response = await handleRequest(
    new Request("https://search.example/v1/search", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-flowchat-install-id": "install-123"
      },
      body: JSON.stringify({ query: "Android news" })
    }),
    {
      TAVILY_API_KEY: "tvly-secret",
      INSTALL_RATE_LIMITER: allowedLimiter,
      IP_RATE_LIMITER: allowedLimiter
    },
    async (_url, options) => {
      upstreamAuthorization = options.headers.Authorization;
      return Response.json({
        query: "Android news",
        results: Array.from({ length: 7 }, (_, index) => ({
          title: `Result ${index}`,
          url: `https://example.com/${index}`,
          content: "Summary",
          raw_content: "must not leak"
        }))
      });
    }
  );

  assert.equal(response.status, 200);
  assert.equal(upstreamAuthorization, "Bearer tvly-secret");
  const body = await response.json();
  assert.equal(body.results.length, 5);
  assert.equal("raw_content" in body.results[0], false);
});

test("rejects requests without an anonymous install id", async () => {
  const response = await handleRequest(
    new Request("https://search.example/v1/search", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ query: "Android news" })
    }),
    {
      TAVILY_API_KEY: "tvly-secret",
      INSTALL_RATE_LIMITER: allowedLimiter,
      IP_RATE_LIMITER: allowedLimiter
    },
    async () => Response.json({})
  );

  assert.equal(response.status, 400);
});

test("returns 429 when the per-install limit is exhausted", async () => {
  const response = await handleRequest(
    new Request("https://search.example/v1/search", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-flowchat-install-id": "install-123"
      },
      body: JSON.stringify({ query: "Android news" })
    }),
    {
      TAVILY_API_KEY: "tvly-secret",
      INSTALL_RATE_LIMITER: { limit: async () => ({ success: false }) },
      IP_RATE_LIMITER: allowedLimiter
    },
    async () => Response.json({})
  );

  assert.equal(response.status, 429);
});
