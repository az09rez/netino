// Netino's service on Cloudflare Workers (deploy steps: README.md next to this file).
//   GET  /sub, /sub/plain   the free list (base64 / plain), mirrored from GitHub and ordered for the requester's ISP
//   POST /results           anonymous results of the free list: { net, results: [{ h, ok, fail, ms }] }
//   POST /issue             a problem report from the app, forwarded to the developer's Telegram
// Nothing identifying is stored: results are kept per ISP (its AS number) and config hash, never per IP.

const SOURCES = [
  "https://raw.githubusercontent.com/az09rez/netino/configs/sub_plain.txt",
  "https://cdn.jsdelivr.net/gh/az09rez/netino@configs/sub_plain.txt",
];
const KEEP_DAYS = 3;          // results older than this stop counting (the list and the networks change)
const MAX_RESULTS = 200;      // per report
const ISSUES_PER_HOUR = 5;    // per connection, against floods

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    try {
      if (request.method === "GET" && (url.pathname === "/sub" || url.pathname === "/sub.txt")) return sub(request, env, ctx, false);
      if (request.method === "GET" && url.pathname === "/sub/plain") return sub(request, env, ctx, true);
      if (request.method === "POST" && url.pathname === "/results") return results(request, env);
      if (request.method === "POST" && url.pathname === "/issue") return issue(request, env);
      if (request.method === "GET" && url.pathname === "/") return home(url);
      return new Response("not found", { status: 404 });
    } catch (e) {
      return new Response("error: " + (e && e.message), { status: 500 });
    }
  },
  // Daily: forget old results
  async scheduled(event, env) {
    if (env.DB) await env.DB.prepare("DELETE FROM stats WHERE updated < ?").bind(Date.now() - KEEP_DAYS * 86400000).run();
  },
};

/** The plain list from GitHub, cached at the edge for 5 minutes (it changes every 2 hours). */
async function plainList(ctx) {
  const cache = caches.default;
  const key = new Request("https://netino.cache/plain");
  const hit = await cache.match(key);
  if (hit) return hit.text();
  for (const u of SOURCES) {
    const r = await fetch(u, { cf: { cacheTtl: 300 } }).catch(() => null);
    if (!r || !r.ok) continue;
    const text = await r.text();
    if (!text.includes("://")) continue;
    ctx.waitUntil(cache.put(key, new Response(text, { headers: { "Cache-Control": "max-age=300" } })));
    return text;
  }
  throw new Error("list unavailable");
}

/** The ISP the request comes from, only for connections from Iran (results through a VPN abroad say nothing). */
function isp(request) {
  const cf = request.cf || {};
  return cf.country === "IR" && cf.asn ? String(cf.asn) : null;
}

async function sub(request, env, ctx, plain) {
  let lines = (await plainList(ctx)).split("\n").map((l) => l.trim()).filter(Boolean);
  const asn = isp(request);
  if (asn && env.DB) {
    // What worked on this ISP first (smoothed success rate), untested ones in the middle, failing ones last;
    // the list's own order (estimated delay) breaks ties
    const rows = await env.DB.prepare("SELECT h, ok, fail FROM stats WHERE asn = ?").bind(asn).all();
    const stats = new Map(rows.results.map((r) => [r.h, r]));
    const keys = await Promise.all(lines.map(configKey));
    const score = (i) => { const s = stats.get(keys[i]); return s ? (s.ok + 1) / (s.ok + s.fail + 2) : 0.5; };
    lines = lines.map((l, i) => [l, score(i), i]).sort((a, b) => b[1] - a[1] || a[2] - b[2]).map((x) => x[0]);
  }
  const body = lines.join("\n") + "\n";
  return new Response(plain ? body : b64(body), {
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control": "no-store",
      "profile-title": "base64:" + b64("Netino"),
      "profile-update-interval": "2",
    },
  });
}

async function results(request, env) {
  if (!env.DB) return new Response(null, { status: 204 });
  const asn = isp(request);
  if (!asn) return new Response(null, { status: 204 });
  const data = await request.json();
  const list = Array.isArray(data.results) ? data.results.slice(0, MAX_RESULTS) : [];
  const now = Date.now();
  const stmt = env.DB.prepare(
    "INSERT INTO stats (asn, h, ok, fail, ms, updated) VALUES (?, ?, ?, ?, ?, ?) " +
    "ON CONFLICT (asn, h) DO UPDATE SET ok = ok + excluded.ok, fail = fail + excluded.fail, " +
    "ms = CASE WHEN excluded.ms > 0 AND (ms = 0 OR excluded.ms < ms) THEN excluded.ms ELSE ms END, updated = excluded.updated");
  const batch = [];
  for (const r of list) {
    if (typeof r.h !== "string" || !/^[0-9a-f]{16}$/.test(r.h)) continue;
    const ok = Math.min(Math.max(r.ok | 0, 0), 20), fail = Math.min(Math.max(r.fail | 0, 0), 20), ms = Math.min(Math.max(r.ms | 0, 0), 30000);
    if (ok + fail === 0) continue;
    batch.push(stmt.bind(asn, r.h, ok, fail, ms, now));
  }
  if (batch.length) await env.DB.batch(batch);
  return new Response(null, { status: 204 });
}

async function issue(request, env) {
  if (!env.TG_TOKEN || !env.TG_CHAT) return new Response("not configured", { status: 503 });
  // A few per connection per hour (the cache API as a counter: approximate, enough against floods)
  const ip = request.headers.get("CF-Connecting-IP") || "?";
  const hour = Math.floor(Date.now() / 3600000);
  const counterKey = new Request(`https://netino.cache/issue/${await sha256hex(ip + hour)}`);
  const seen = await caches.default.match(counterKey);
  const n = seen ? parseInt(await seen.text(), 10) : 0;
  if (n >= ISSUES_PER_HOUR) return new Response("too many", { status: 429 });
  await caches.default.put(counterKey, new Response(String(n + 1), { headers: { "Cache-Control": "max-age=3600" } }));

  const d = await request.json();
  const report = String(d.report || "").slice(0, 60000);
  const cf = request.cf || {};
  const caption = [
    "Netino report", String(d.app || "").slice(0, 80),
    `${cf.country || "?"} • AS${cf.asn || "?"} ${cf.asOrganization || ""}`,
    d.contact ? "Contact: " + String(d.contact).slice(0, 200) : "",
    d.note ? "Note: " + String(d.note).slice(0, 700) : "",
  ].filter(Boolean).join("\n");
  const form = new FormData();
  form.append("chat_id", env.TG_CHAT);
  form.append("caption", caption.slice(0, 1000));
  form.append("document", new Blob([report], { type: "text/plain" }), "netino-report.txt");
  const r = await fetch(`https://api.telegram.org/bot${env.TG_TOKEN}/sendDocument`, { method: "POST", body: form });
  return new Response(null, { status: r.ok ? 204 : 502 });
}

function home(url) {
  const base = url.origin;
  return new Response(
    `Netino\n\nFree configs (updated every 2 hours):\n  ${base}/sub        (subscription link: v2rayNG, v2rayN, Hiddify, NekoBox, ...)\n  ${base}/sub/plain  (one config per line)\n`,
    { headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

/** Same as the apps' NetinoApi.configKey: the link without its name (vmess: its fields except "ps", sorted). */
async function configKey(link) {
  let canonical = link.split("#")[0];
  if (link.toLowerCase().startsWith("vmess://")) {
    try {
      let raw = link.slice(8).split("#")[0].replace(/-/g, "+").replace(/_/g, "/").replace(/\s/g, "");
      raw += "=".repeat((4 - (raw.length % 4)) % 4);
      const o = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(raw), (c) => c.charCodeAt(0))));
      canonical = "vmess|" + Object.keys(o).filter((k) => k !== "ps").sort()
        .map((k) => k + "=" + (o[k] !== null && typeof o[k] === "object" ? JSON.stringify(o[k]) : String(o[k]))).join("&");
    } catch (e) { canonical = link.trim(); }
  }
  return (await sha256hex(canonical)).slice(0, 16);
}

async function sha256hex(s) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function b64(s) {
  const bytes = new TextEncoder().encode(s);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}
