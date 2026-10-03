#!/usr/bin/env python3
"""
Netino config collector: builds the app's built-in subscription.

  recheck   re-test every config in the list and drop the ones that stopped working (every 2 h)
  discover  recheck, then crawl public sources for new configs and add the ones that pass (daily)

A config is kept only if BOTH tests pass:
  1. Real delay: an HTTP request through the config itself (Xray core on the runner), which proves
     the protocol, keys and transport work - not just that a port is open.
  2. Reachability from Iran: TCP connect to the server from check-host.net's nodes in Iran. The
     domain is resolved inside Iran, so servers whose IP or domain is filtered there fail.
The list is ordered by the TCP latency measured from Iran.

Standard library only; needs `xray` and `curl`.
"""
import argparse
import base64
import concurrent.futures as cf
import hashlib
import html
import json
import os
import random
import re
import socket
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

SCHEMES = ("vless", "vmess", "trojan", "ss")
LINK_RE = re.compile(r"(?:vless|vmess|trojan|ss)://[^\s<>\"'`]+")
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
TEST_URL = "https://www.gstatic.com/generate_204"

MAX_PER_SOURCE = 20000
MAX_NEW_TESTS = 3000      # new candidates tested per discovery run
MAX_IR_CHECKS = 200       # new configs sent to the Iran check per discovery run
MAX_KEEP = 50             # size of the published list (the best Iran pings are kept)
BATCH = 250
CURL_WORKERS = 48
IR_WORKERS = 3


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def http_get(url, timeout=30, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(16 * 1024 * 1024).decode("utf-8", "ignore")


def b64decode(s):
    s = re.sub(r"\s+", "", s)
    s += "=" * (-len(s) % 4)
    for fn in (base64.b64decode, base64.urlsafe_b64decode):
        try:
            return fn(s).decode("utf-8", "ignore")
        except Exception:
            pass
    return None


# ---------------------------------------------------------------- sources

def read_sources(path):
    """[(source, iran_tested)]: a line starting with "iran " is a list maintained for use inside Iran."""
    out = []
    for l in (l.strip() for l in Path(path).read_text().splitlines()):
        if not l or l.startswith("#"):
            continue
        out.append((l[5:].strip(), True) if l.startswith("iran ") else (l, False))
    return out


def fetch_source(src):
    url = f"https://t.me/s/{src[3:]}" if src.startswith("tg:") else src
    text = http_get(url)
    if src.startswith("tg:"):
        text = html.unescape(re.sub(r"<br\s*/?>", "\n", text))
    elif "://" not in text[:5000]:
        text = b64decode(text) or text
    links = LINK_RE.findall(text)
    # Telegram posts often glue punctuation to the end of a link
    return [l.rstrip(".,;)]}") for l in links][:MAX_PER_SOURCE]


def crawl(sources):
    """[(link, iran_tested)] from every source; failing sources are skipped."""
    links = []
    with cf.ThreadPoolExecutor(8) as ex:
        futs = {ex.submit(fetch_source, src): (src, iran) for src, iran in sources}
        for f in cf.as_completed(futs):
            src, iran = futs[f]
            try:
                got = f.result()
                log(f"  {len(got):6d}  {'[iran] ' if iran else ''}{src}")
                links += [(l, iran) for l in got]
            except Exception as e:
                log(f"  failed  {src}: {e}")
    return links


def dpi_fragile(c):
    """
    Plain transports that Iranian DPI identifies and blocks quickly: Shadowsocks, and VLESS / VMess /
    Trojan over raw TCP / KCP / QUIC without TLS or Reality. CDN transports (ws, httpupgrade, grpc,
    xhttp) are kept even without TLS, since they work behind Iranian CDNs.
    """
    x = c["x"]
    if c["proto"] == "ss":
        return True
    if x.get("security") in ("tls", "reality"):
        return False
    return (x.get("network") or "tcp") in ("tcp", "raw", "kcp", "quic", "")


def tier(c, iran_tested):
    """0 = from a list maintained for Iran, 1 = TLS / Reality, 2 = everything else."""
    if iran_tested:
        return 0
    return 1 if c["x"].get("security") in ("tls", "reality") else 2


# ---------------------------------------------------------------- parsing (mirrors the app's LinkParser)

def split_host_port(s):
    if s.startswith("["):
        host, _, rest = s[1:].partition("]")
        port = rest[1:] if rest.startswith(":") else ""
    else:
        host, _, port = s.rpartition(":")
    try:
        p = int(port)
    except ValueError:
        return None
    return (host, p) if host and 0 < p < 65536 else None


def udecode(s):
    return urllib.parse.unquote(s.replace("+", "%2B"))


def parse(link):
    scheme = link.split("://", 1)[0].lower()
    try:
        if scheme == "vmess":
            return parse_vmess(link)
        if scheme in ("vless", "trojan"):
            return parse_std(link, scheme)
        if scheme == "ss":
            return parse_ss(link)
    except Exception:
        return None
    return None


def parse_vmess(link):
    o = json.loads(b64decode(link[8:]) or "{}")
    f = lambda k: str(o.get(k, "") or "")
    hp = (f("add"), int(f("port") or 0))
    if not hp[0] or not 0 < hp[1] < 65536 or not f("id"):
        return None
    tls = f("tls")
    x = dict(uuid=f("id"), alterId=int(f("aid") or 0), method=f("scy") or "auto", network=f("net") or "tcp",
             headerType=f("type"), host=f("host"), path=f("path"), serviceName=f("path"),
             security="" if tls == "none" else tls, sni=f("sni"), alpn=f("alpn"), fingerprint=f("fp"))
    return dict(proto="vmess", address=hp[0], port=hp[1], x=x)


def parse_std(link, proto):
    rest = link.split("://", 1)[1]
    main = rest.split("#", 1)[0]
    query = {}
    if "?" in main:
        main, qs = main.split("?", 1)
        for part in qs.split("&"):
            if "=" in part:
                k, v = part.split("=", 1)
                query[k] = udecode(v)
    main = main.rstrip("/")
    if "@" not in main:
        return None
    user, hostport = main.rsplit("@", 1)
    hp = split_host_port(hostport)
    if not hp or not user:
        return None
    q = query.get
    sec = q("security") or ("tls" if proto == "trojan" else "")
    x = dict(uuid=udecode(user), encryption=q("encryption") or "none", flow=q("flow", ""), network=q("type") or "tcp",
             security="" if sec == "none" else sec, sni=q("sni") or q("peer", ""), alpn=q("alpn", ""),
             fingerprint=q("fp", ""), publicKey=q("pbk", ""), shortId=q("sid", ""), spiderX=q("spx", ""),
             host=q("host", ""), path=q("path", ""), headerType=q("headerType", ""), serviceName=q("serviceName", ""),
             mode=q("mode", ""), allowInsecure=q("allowInsecure") == "1" or q("insecure") == "1")
    if x["security"] == "reality" and not x["publicKey"]:
        return None
    return dict(proto=proto, address=hp[0], port=hp[1], x=x)


def parse_ss(link):
    raw = link[5:]
    main = raw.split("#", 1)[0].split("?", 1)[0].rstrip("/")
    if "@" not in main:
        main = b64decode(main) or ""
    if "@" not in main:
        return None
    userinfo, hostport = main.rsplit("@", 1)
    creds = udecode(userinfo) if ":" in userinfo else (b64decode(userinfo) or "")
    hp = split_host_port(hostport)
    if not hp or ":" not in creds:
        return None
    method, password = creds.split(":", 1)
    return dict(proto="ss", address=hp[0], port=hp[1], x=dict(method=method, uuid=password))


def key_of(c):
    x = c["x"]
    return "|".join(str(v) for v in (c["proto"], c["address"].lower(), c["port"], x.get("uuid"), x.get("network"),
                                      x.get("path"), x.get("host"), x.get("sni"), x.get("security"), x.get("publicKey")))


# ---------------------------------------------------------------- Xray outbound (mirrors XrayConfigBuilder)

def outbound(c, tag):
    x, a, p = c["x"], c["address"], c["port"]
    proto = c["proto"]
    if proto == "vmess":
        settings = {"vnext": [{"address": a, "port": p, "users": [
            {"id": x["uuid"], "alterId": x.get("alterId", 0), "security": x.get("method") or "auto"}]}]}
    elif proto == "vless":
        u = {"id": x["uuid"], "encryption": x.get("encryption") or "none"}
        if x.get("flow"):
            u["flow"] = x["flow"]
        settings = {"vnext": [{"address": a, "port": p, "users": [u]}]}
    elif proto == "trojan":
        s = {"address": a, "port": p, "password": x["uuid"]}
        if x.get("flow"):
            s["flow"] = x["flow"]
        settings = {"servers": [s]}
    else:
        settings = {"servers": [{"address": a, "port": p, "method": x["method"], "password": x["uuid"]}]}
    out = {"tag": tag, "protocol": "shadowsocks" if proto == "ss" else proto, "settings": settings}
    if proto != "ss":
        out["streamSettings"] = stream(c)
    return out


def stream(c):
    x = c["x"]
    net = x.get("network") or "tcp"
    net = "h2" if net == "http" else net
    path = x.get("path") or "/"
    # CDN configs often set only the SNI; the Host header needs the domain too (same as the app)
    host = x.get("host") or (x.get("sni", "") if not is_ip(x.get("sni", "")) else "")
    s = {"network": net}
    if net == "ws":
        s["wsSettings"] = {"path": path, **({"host": host} if host else {})}
    elif net == "grpc":
        s["grpcSettings"] = {"serviceName": x.get("serviceName") or x.get("path", ""), "multiMode": x.get("mode") == "multi"}
    elif net == "httpupgrade":
        s["httpupgradeSettings"] = {"path": path, **({"host": host} if host else {})}
    elif net in ("xhttp", "splithttp"):
        s["xhttpSettings"] = {"path": path, "mode": x.get("mode") or "auto", **({"host": host} if host else {})}
    elif net == "h2":
        s["httpSettings"] = {"path": path, **({"host": [h.strip() for h in host.split(",")]} if host else {})}
    elif net == "kcp":
        s["kcpSettings"] = {"header": {"type": x.get("headerType") or "none"}, **({"seed": x["path"]} if x.get("path") else {})}
    elif net in ("tcp", "raw") and x.get("headerType") == "http":
        s["tcpSettings"] = {"header": {"type": "http", "request": {"path": [path], "headers": {"Host": [host or c["address"]]}}}}
    sec = x.get("security", "")
    if sec == "tls":
        t = {"serverName": x.get("sni") or host.split(",")[0].strip() or c["address"], "fingerprint": x.get("fingerprint") or "chrome"}
        # Xray 26 rejects "allowInsecure"; such configs are pinned to the certificate the server presents
        if c.get("pin"):
            t["pinnedPeerCertSha256"] = c["pin"]
        if x.get("alpn"):
            t["alpn"] = [v.strip() for v in x["alpn"].split(",")]
        s["security"], s["tlsSettings"] = "tls", t
    elif sec == "reality":
        s["security"] = "reality"
        s["realitySettings"] = {"serverName": x.get("sni", ""), "fingerprint": x.get("fingerprint") or "chrome",
                                "publicKey": x.get("publicKey", ""), "shortId": x.get("shortId", ""), "spiderX": x.get("spiderX", "")}
    else:
        s["security"] = "none"
    return s


# ---------------------------------------------------------------- certificate pins for allowInsecure configs

def is_ip(s):
    return bool(s) and (":" in s or all(ch.isdigit() or ch == "." for ch in s))


def cert_pin(c):
    x = c["x"]
    sni = x.get("sni") or x.get("host", "").split(",")[0].strip() or c["address"]
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    try:
        with socket.create_connection((c["address"], c["port"]), timeout=6) as raw:
            with ctx.wrap_socket(raw, server_hostname=None if is_ip(sni) else sni) as t:
                return hashlib.sha256(t.getpeercert(binary_form=True)).hexdigest()
    except Exception:
        return None


def add_pins(configs):
    insecure = [c for c in configs if c["x"].get("security") == "tls" and c["x"].get("allowInsecure")]
    with cf.ThreadPoolExecutor(32) as ex:
        for c, pin in zip(insecure, ex.map(cert_pin, insecure)):
            c["pin"] = pin
    log(f"  certificate pins: {sum(1 for c in insecure if c.get('pin'))}/{len(insecure)} insecure-TLS configs")


# ---------------------------------------------------------------- real delay through Xray

def xray_config(items, base_port):
    return {
        "log": {"loglevel": "none"},
        "inbounds": [{"tag": f"in{i}", "listen": "127.0.0.1", "port": base_port + i, "protocol": "socks",
                      "settings": {"udp": False}} for i in range(len(items))],
        "outbounds": [outbound(c, f"out{i}") for i, c in enumerate(items)],
        "routing": {"rules": [{"type": "field", "inboundTag": [f"in{i}"], "outboundTag": f"out{i}"} for i in range(len(items))]},
    }


def xray_accepts(xray, items, workdir):
    path = Path(workdir, "check.json")
    path.write_text(json.dumps(xray_config(items, 20000)))
    r = subprocess.run([xray, "run", "-test", "-c", str(path)], capture_output=True, timeout=60)
    return r.returncode == 0


def valid_subset(xray, items, workdir):
    """Xray refuses the whole file if one outbound is malformed: bisect until only valid ones remain."""
    if not items:
        return []
    if xray_accepts(xray, items, workdir):
        return items
    if len(items) == 1:
        return []
    mid = len(items) // 2
    return valid_subset(xray, items[:mid], workdir) + valid_subset(xray, items[mid:], workdir)


def curl_delay(port):
    best = None
    for _ in range(2):
        try:
            r = subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code} %{time_total}", "-x",
                                f"socks5h://127.0.0.1:{port}", "--connect-timeout", "8", "-m", "10", TEST_URL],
                               capture_output=True, text=True, timeout=15)
            code, t = r.stdout.split()
            if code == "204":
                ms = int(float(t) * 1000)
                best = ms if best is None else min(best, ms)
        except Exception:
            pass
        if best is None:
            break   # first try failed: don't spend a second one
    return best


def wait_port(port, timeout=10):
    end = time.time() + timeout
    while time.time() < end:
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                return True
        time.sleep(0.2)
    return False


def real_delays(xray, configs):
    """Returns {key: delay_ms} for every config whose request through Xray succeeded."""
    result = {}
    with tempfile.TemporaryDirectory() as wd:
        for start in range(0, len(configs), BATCH):
            items = valid_subset(xray, configs[start:start + BATCH], wd)
            if not items:
                continue
            path = Path(wd, "run.json")
            path.write_text(json.dumps(xray_config(items, 20000)))
            proc = subprocess.Popen([xray, "run", "-c", str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                if not wait_port(20000 + len(items) - 1):
                    log("  xray did not start")
                    continue
                with cf.ThreadPoolExecutor(CURL_WORKERS) as ex:
                    for c, ms in zip(items, ex.map(curl_delay, [20000 + i for i in range(len(items))])):
                        if ms is not None:
                            result[c["key"]] = ms
            finally:
                proc.kill()
                proc.wait()
            log(f"  real delay: {min(start + BATCH, len(configs))}/{len(configs)} tested, {len(result)} working")
    return result


# ---------------------------------------------------------------- reachability from Iran (check-host.net)

class IranCheck:
    API = "https://check-host.net"

    def __init__(self):
        self.nodes = []
        self.limited = False
        try:
            data = json.loads(http_get(f"{self.API}/nodes/hosts", headers={"Accept": "application/json"}))
            for name, info in data.get("nodes", {}).items():
                loc = info.get("location", []) if isinstance(info, dict) else []
                if loc and str(loc[0]).lower() == "ir":
                    self.nodes.append(name)
        except Exception as e:
            log(f"  check-host nodes unavailable: {e}")
        log(f"  Iran nodes: {self.nodes or 'none'}")

    def tcp_ms(self, host, port):
        """(ms, verdict): verdict is 'ok', 'blocked' (every Iranian node failed) or 'unknown'."""
        if not self.nodes or self.limited:
            return None, "unknown"
        target = f"[{host}]:{port}" if ":" in host else f"{host}:{port}"
        q = urllib.parse.urlencode([("host", target)] + [("node", n) for n in self.nodes])
        try:
            r = json.loads(http_get(f"{self.API}/check-tcp?{q}", headers={"Accept": "application/json"}))
            rid = r.get("request_id")
            if not rid:
                if "limit" in json.dumps(r).lower():
                    self.limited = True
                return None, "unknown"
            res = {}
            for _ in range(10):
                time.sleep(2.5)
                res = json.loads(http_get(f"{self.API}/check-result/{rid}", headers={"Accept": "application/json"}))
                if all(res.get(n) is not None for n in self.nodes):
                    break
        except Exception as e:
            log(f"  check-host error: {e}")
            return None, "unknown"
        times, answered = [], 0
        for n in self.nodes:
            v = res.get(n)
            if not isinstance(v, list) or not v:
                continue
            answered += 1
            first = v[0]
            if isinstance(first, dict) and first.get("time") is not None:
                times.append(int(float(first["time"]) * 1000))
        if times:
            return max(1, min(times)), "ok"
        return None, "blocked" if answered else "unknown"

    def check_all(self, configs):
        out = {}
        with cf.ThreadPoolExecutor(IR_WORKERS) as ex:
            futs = {ex.submit(self.tcp_ms, c["address"], c["port"]): c for c in configs}
            for f in cf.as_completed(futs):
                out[futs[f]["key"]] = f.result()
        return out


# ---------------------------------------------------------------- output

def rename(link, name):
    if link.startswith("vmess://"):
        o = json.loads(b64decode(link[8:]))
        o["ps"] = name
        return "vmess://" + base64.b64encode(json.dumps(o, ensure_ascii=False).encode()).decode()
    return link.split("#", 1)[0] + "#" + urllib.parse.quote(name)


def write_output(out, entries):
    # Iran-maintained sources first, then TLS/Reality, then the rest; within a tier by ping from Iran
    entries.sort(key=lambda e: (e.get("tier", 2), e["ir"] is None, e["ir"] or 0, e["delay"]))
    entries[:] = entries[:MAX_KEEP]
    lines = []
    for i, e in enumerate(entries, 1):
        ping = f"IR {e['ir']}ms" if e["ir"] is not None else f"{e['delay']}ms"
        mark = " | IR-list" if e.get("tier") == 0 else ""
        lines.append(rename(e["link"], f"Netino {i:02d} | {e['proto'].upper()} | {ping}{mark}"))
    plain = "\n".join(lines) + "\n"
    out.mkdir(parents=True, exist_ok=True)
    (out / "sub_plain.txt").write_text(plain)
    (out / "sub.txt").write_text(base64.b64encode(plain.encode()).decode() + "\n")
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    (out / "state.json").write_text(json.dumps({"updated": now, "configs": entries}, indent=1, ensure_ascii=False))
    (out / "README.md").write_text(
        f"# Netino built-in subscription\n\nUpdated {now} — {len(entries)} configs, each passing a real-delay test "
        "and a TCP check from Iran (check-host.net). Generated by `.github/workflows/configs.yml` on the main branch.\n\n"
        "Subscription link: `https://raw.githubusercontent.com/az09rez/netino/configs/sub.txt`\n")


# ---------------------------------------------------------------- main

def test(xray, configs, ir, ir_limit=None):
    """Real delay first (cheap, local), then the Iran check for the ones that work."""
    add_pins(configs)
    delays = real_delays(xray, configs)
    # Configs from Iran-maintained lists always get their Iran check, then the fastest of the rest
    working = sorted((c for c in configs if c["key"] in delays), key=lambda c: (not c.get("iran"), delays[c["key"]]))
    if ir_limit is not None:
        working = working[:ir_limit]
    log(f"  Iran check for {len(working)} configs")
    verdicts = ir.check_all(working)
    return delays, working, verdicts


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["recheck", "discover"])
    ap.add_argument("--xray", default="xray")
    ap.add_argument("--sources", default=str(Path(__file__).with_name("sources.txt")))
    ap.add_argument("--out", default="out")
    args = ap.parse_args()
    out = Path(args.out)

    # Sanity: without internet on the runner every config would look dead and the list would be wiped
    if subprocess.run(["curl", "-s", "-o", "/dev/null", "-m", "10", TEST_URL]).returncode != 0:
        sys.exit("runner has no internet access; keeping the current list")

    state_file = out / "state.json"
    old = json.loads(state_file.read_text())["configs"] if state_file.exists() else []
    ir = IranCheck()
    now = int(time.time())

    # 1. re-test the current list; a config that fails either test is removed
    existing = []
    for e in old:
        c = parse(e["link"])
        if c and not dpi_fragile(c):
            c.update(key=key_of(c), link=e["link"], added=e.get("added", now), iran=e.get("tier") == 0)
            existing.append(c)
    log(f"recheck: {len(existing)} configs")
    delays, working, verdicts = test(args.xray, existing, ir)
    kept = []
    for c in working:
        ms, verdict = verdicts.get(c["key"], (None, "unknown"))
        if verdict == "blocked":
            continue
        prev = next((e for e in old if e["link"] == c["link"]), {})
        kept.append(dict(link=c["link"], key=c["key"], proto=c["proto"], added=c["added"], checked=now,
                         delay=delays[c["key"]], ir=ms if ms is not None else prev.get("ir"), tier=tier(c, c["iran"])))
    log(f"recheck: kept {len(kept)}/{len(existing)}")
    if args.mode == "recheck" and len(existing) >= 10 and not kept:
        sys.exit("every config failed at once (more likely a runner/network problem); keeping the current list")

    # 2. daily: crawl, test and add new configs
    if args.mode == "discover":
        log("crawling sources")
        links = crawl(read_sources(args.sources))
        known = {e["key"] for e in kept}
        fresh = {}
        fragile = 0
        for l, iran in links:
            c = parse(l)
            if not c:
                continue
            if dpi_fragile(c):
                fragile += 1
                continue
            c["key"] = key_of(c)
            if c["key"] in known:
                continue
            if c["key"] in fresh:
                fresh[c["key"]]["iran"] |= iran
            else:
                c.update(link=l, added=now, iran=iran)
                fresh[c["key"]] = c
        # Every config from an Iran-maintained list is tested; the rest is sampled
        iran_first = [c for c in fresh.values() if c["iran"]]
        others = [c for c in fresh.values() if not c["iran"]]
        random.shuffle(others)
        candidates = (iran_first + others)[:MAX_NEW_TESTS]
        log(f"discover: {len(links)} links, {fragile} DPI-fragile skipped, {len(fresh)} new unique "
            f"({len(iran_first)} from Iran lists), testing {len(candidates)}")
        delays, working, verdicts = test(args.xray, candidates, ir, ir_limit=MAX_IR_CHECKS)
        added = 0
        for c in working:
            ms, verdict = verdicts.get(c["key"], (None, "unknown"))
            if verdict != "ok":   # new configs need a positive answer from Iran
                continue
            kept.append(dict(link=c["link"], key=c["key"], proto=c["proto"], added=now, checked=now,
                             delay=delays[c["key"]], ir=ms, tier=tier(c, c["iran"])))
            added += 1
        log(f"discover: added {added}")

    write_output(out, kept)
    log(f"published {len(kept)} configs")


if __name__ == "__main__":
    main()
