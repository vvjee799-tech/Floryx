const INSTALL_ID_HEADER = "x-flowchat-install-id";
const MAX_QUERY_LENGTH = 800;
const MAX_CONTENT_LENGTH = 1600;

export async function handleRequest(request, env, fetchImpl = fetch) {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname === "/health") {
    return json({ ok: true });
  }
  if (request.method !== "POST" || url.pathname !== "/v1/search") {
    return json({ error: "Not found" }, 404);
  }

  const installId = request.headers.get(INSTALL_ID_HEADER)?.trim() ?? "";
  if (!/^[A-Za-z0-9-]{8,100}$/.test(installId)) {
    return json({ error: "Invalid install id" }, 400);
  }

  const installLimit = await env.INSTALL_RATE_LIMITER.limit({ key: installId });
  if (!installLimit.success) return json({ error: "Rate limit exceeded" }, 429);
  const ip = request.headers.get("cf-connecting-ip") || "unknown";
  const ipLimit = await env.IP_RATE_LIMITER.limit({ key: ip });
  if (!ipLimit.success) return json({ error: "Rate limit exceeded" }, 429);

  const body = await request.json().catch(() => null);
  const query = typeof body?.query === "string" ? body.query.trim() : "";
  if (!query || query.length > MAX_QUERY_LENGTH) {
    return json({ error: "Invalid query" }, 400);
  }
  if (!env.TAVILY_API_KEY) return json({ error: "Search unavailable" }, 503);

  const upstream = await fetchImpl("https://api.tavily.com/search", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.TAVILY_API_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      query,
      search_depth: "basic",
      max_results: 5,
      include_answer: false,
      include_raw_content: false,
      include_images: false
    }),
    signal: AbortSignal.timeout(12000)
  });
  if (!upstream.ok) return json({ error: "Search unavailable" }, 502);

  const data = await upstream.json();
  const results = Array.isArray(data.results)
    ? data.results
        .filter((item) => item && typeof item.url === "string" && /^https?:\/\//.test(item.url))
        .slice(0, 5)
        .map((item) => ({
          title: typeof item.title === "string" ? item.title.slice(0, 300) : item.url,
          url: item.url,
          content: typeof item.content === "string" ? item.content.slice(0, MAX_CONTENT_LENGTH) : ""
        }))
    : [];
  return json({ query, results });
}

function json(body, status = 200) {
  return Response.json(body, {
    status,
    headers: { "Cache-Control": "no-store" }
  });
}

export default {
  fetch(request, env) {
    return handleRequest(request, env);
  }
};
