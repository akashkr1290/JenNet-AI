// JanNet AI pilot - HTTPS front door for the EC2 API (no domain needed).
//
// Only /api/v1/* is forwarded; everything else is 404. The request goes to
// ORIGIN_URL (plain HTTP to nginx on the EC2 host) with its method, headers and
// body unchanged, plus two headers:
//   X-JanNet-Client-IP  the caller's real IP (Cloudflare's CF-Connecting-IP)
//   X-JanNet-Proxy-Key  shared secret (Worker secret PROXY_KEY = SSM WORKER_PROXY_KEY)
// nginx only uses X-JanNet-Client-IP as the backend's X-Real-IP when the key
// matches, so a direct caller cannot spoof its IP. Both headers are always
// overwritten here, so a browser cannot inject them either.
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!url.pathname.startsWith("/api/v1/")) {
      return new Response("Not found", { status: 404 });
    }
    const upstream = new Request(env.ORIGIN_URL + url.pathname + url.search, request);
    upstream.headers.set("X-JanNet-Client-IP", request.headers.get("CF-Connecting-IP") || "");
    upstream.headers.set("X-JanNet-Proxy-Key", env.PROXY_KEY || "");
    return fetch(upstream);
  },
};
