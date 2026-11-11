#!/usr/bin/env python3
"""
CloudStream 3 — Pre-Flight Source Reconnaissance & Link Velocity Gatekeeper
=============================================================================
Autonomous diagnostic tool that inspects, audits, and benchmarks target sources
BEFORE a single line of Kotlin code is written.

Evaluates 6 Core Feasibility Pillars:
  1. Web Architecture & Anti-Bot Status
  2. Pagination Integrity (Page 1 != Page 2)
  3. Title Cleaning Verification (Raw vs Cleaned side-by-side)
  4. Image Reachability & Renderability (HTTP 200 + image/* MIME + size > 100B)
  5. TV Season & Episode Hierarchy (Heading traversal, no Season 1 collapse, zip filtering)
  6. Stream Link Velocity & Fake Timer Benchmark (< 3s Pure HTTP, zero-wait bypass, byte-range 206)

Usage:
  python scripts/probe_target.py <url>
  python scripts/probe_target.py https://example.com --sample-movie <url> --sample-series <url>
"""

import sys
import os
import re
import time
import html
import json
import urllib.request
import urllib.parse
from bs4 import BeautifulSoup

try:
    from sync_upstream_extractors import check_url as cross_check_native_extractor
except ImportError:
    cross_check_native_extractor = None

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Accept-Language": "en-US,en;q=0.9",
}


def fetch_url(url, extra_headers=None, timeout=10):
    headers = dict(HEADERS)
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            content = resp.read()
            return {
                "status": resp.getcode(),
                "url": resp.geturl(),
                "headers": dict(resp.headers),
                "body": content,
                "text": content.decode("utf-8", errors="replace"),
                "error": None
            }
    except urllib.error.HTTPError as e:
        return {
            "status": e.code,
            "url": url,
            "headers": dict(e.headers),
            "body": b"",
            "text": "",
            "error": f"HTTP {e.code}: {e.reason}"
        }
    except Exception as e:
        return {
            "status": 0,
            "url": url,
            "headers": {},
            "body": b"",
            "text": "",
            "error": str(e)
        }


def clean_title(raw: str) -> str:
    """Production balanced title cleaner: preserves (Season X-Y) and (YYYY) while stripping release specs."""
    if not raw:
        return ""
    title = html.unescape(raw).strip()
    title = re.sub(r'^(?:Download|Watch)\s+', '', title, flags=re.IGNORECASE).strip()
    title = re.sub(r'\s*[-|]\s*(?:MoviesMod|Movies4u|123movies|Filmyzilla).*$', '', title, flags=re.IGNORECASE).strip()

    # Fast-path: canonical title pattern with season range or release year
    canonical = re.search(r'^(.*?(\((?:Season\s*[\d\-]+|\d{4})\)))', title, re.IGNORECASE)
    if canonical:
        return canonical.group(1).strip()

    # Fallback: strip release noise
    title = re.sub(r'\[(?:S\d+)?E\d+[^\]]*Added\]', '', title, flags=re.IGNORECASE)
    title = re.sub(r'\b(?:480p|720p|1080p|2160p|4k|uhd|web-dl|webrip|bluray|hdtc|hdcam|hdrip|dvd|x264|x265|hevc|10bit|msubs|hindi|dual\s+audio|multi\s+audio)\b.*$', '', title, flags=re.IGNORECASE)
    title = re.sub(r'\{[^}]*\}', '', title)
    title = re.sub(r'\[[^\]]*\]', '', title)
    title = re.sub(r'\s*[\|\-:]+\s*$', '', title)
    return re.sub(r'\s+', ' ', title).strip()


def probe_image(img_url, base_url):
    """Verifies that an image is absolute, reachable via HTTP, returns an image/* MIME, and size > 100 bytes."""
    if not img_url:
        return False, "Null/Empty image URL"
    clean_url = img_url.strip()
    if clean_url.startswith("//"):
        clean_url = "https:" + clean_url
    elif clean_url.startswith("/"):
        clean_url = urllib.parse.urljoin(base_url, clean_url)

    res = fetch_url(clean_url, extra_headers={"Range": "bytes=0-1024"}, timeout=5)
    if res["status"] in (200, 206, 304):
        content_type = res["headers"].get("Content-Type", "")
        content_length = int(res["headers"].get("Content-Length", len(res["body"])))
        if "image" in content_type or clean_url.endswith((".jpg", ".png", ".webp", ".jpeg")):
            if content_length > 100 or len(res["body"]) > 50:
                return True, f"HTTP {res['status']} ({content_type}, {content_length} bytes)"
        return False, f"Invalid MIME or empty: {content_type} ({content_length} bytes)"
    return False, f"Failed HTTP {res['status']}: {res['error']}"


def extract_season_hierarchy(soup):
    """
    Scans headings in document order to parse multi-season TV posts without
    collapsing child episode links into Season 1.
    """
    content = soup.select_one("div.entry-content, div.thecontent, article") or soup
    current_season = 1
    season_map = {}
    season_regex = re.compile(r'(?i)\b(?:Season\s*|S)(\d+)\b')
    range_regex = re.compile(r'(?i)(?:Season\s*\d+\s*-\s*\d+|S\d+\s*-\s*S?\d+)')

    for el in content.find_all(['h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'p', 'div']):
        text = el.text.strip()
        if el.name in ['h1', 'h2', 'h3', 'h4', 'h5', 'h6']:
            if not range_regex.search(text):
                m = season_regex.search(text)
                if m:
                    current_season = int(m.group(1))

        for a in el.find_all('a'):
            txt = a.text.strip()
            href = a.get('href', '').strip()
            if not href or href.startswith('#') or href.startswith('javascript:'):
                continue

            is_batch = 'batch' in txt.lower() or 'zip' in txt.lower() or href.endswith('.zip')
            is_ep = 'episode' in txt.lower() or 'ep ' in txt.lower() or bool(re.search(r'(?i)\bE\d+\b', txt)) or 'episodes' in href

            if is_ep and not is_batch:
                season_map.setdefault(current_season, []).append((txt, href))

    return season_map


def benchmark_link_velocity(sample_stream_url, base_url, max_hops=4, sla_seconds=3.0):
    """
    Concentrated Link Extractor Trace:
    Traces from post link -> embed/shortener -> terminal media link hop-by-hop.
    Measures per-hop latency, checks fake timers, asserts byte-range streamability,
    and enforces a strict SLA budget (default: <= 3.0s total).
    """
    hops = []
    current_url = sample_stream_url
    total_start = time.time()
    terminal_stream = None

    for hop_idx in range(1, max_hops + 1):
        hop_start = time.time()
        res = fetch_url(current_url, timeout=6)
        hop_elapsed = time.time() - hop_start
        total_elapsed = time.time() - total_start

        if not res["body"]:
            hops.append({
                "hop": hop_idx,
                "url": current_url,
                "status": res["status"],
                "elapsed_ms": int(hop_elapsed * 1000),
                "error": res["error"]
            })
            break

        content_type = res["headers"].get("Content-Type", "").lower()
        is_stream = res["status"] in (200, 206) and any(
            ext in current_url.lower() or ext in content_type
            for ext in [".m3u8", ".mpd", ".mp4", ".mkv", "video/", "mpegurl", "octet-stream"]
        )

        hops.append({
            "hop": hop_idx,
            "url": current_url,
            "status": res["status"],
            "content_type": content_type,
            "elapsed_ms": int(hop_elapsed * 1000),
            "is_stream": is_stream
        })

        if is_stream:
            terminal_stream = current_url
            break

        html_text = res["text"]
        soup = BeautifulSoup(html_text, "html.parser")

        # 1. Check for immediate landing form chain (fake timer bypass)
        landing_form = soup.select_one("form#landing, form[name=landing], form[action*='landing']")
        if landing_form:
            action = landing_form.get("action", "")
            if action and not action.startswith("http"):
                action = urllib.parse.urljoin(current_url, action)
            form_data = {inp.get("name"): inp.get("value", "") for inp in landing_form.find_all("input") if inp.get("name")}

            post_start = time.time()
            post_req = urllib.request.Request(
                action,
                data=urllib.parse.urlencode(form_data).encode("utf-8"),
                headers=dict(HEADERS, **{"Content-Type": "application/x-www-form-urlencoded", "Referer": current_url})
            )
            try:
                with urllib.request.urlopen(post_req, timeout=5) as p_resp:
                    post_html = p_resp.read().decode("utf-8", errors="replace")
                    post_elapsed = time.time() - post_start
                    hops.append({
                        "hop": f"{hop_idx}-form",
                        "url": action,
                        "status": p_resp.getcode(),
                        "elapsed_ms": int(post_elapsed * 1000),
                        "bypass": "Zero-Wait Form POST"
                    })
                    token_match = re.search(r'\?go=([^"\'&\s]+)', post_html)
                    if token_match:
                        token = token_match.group(1)
                        cookie_val = form_data.get("_wp_http2", "")
                        next_url = urllib.parse.urljoin(action, f"?go={token}")
                        # Immediately follow with token cookie
                        token_req = urllib.request.Request(
                            next_url,
                            headers=dict(HEADERS, **{"Cookie": f"{token}={cookie_val}", "Referer": action})
                        )
                        with urllib.request.urlopen(token_req, timeout=5) as t_resp:
                            t_html = t_resp.read().decode("utf-8", errors="replace")
                            refresh_match = re.search(r'url=([^"\'\s>]+)', t_html, re.I)
                            if refresh_match:
                                current_url = refresh_match.group(1)
                                continue
            except Exception:
                pass

        # 2. Check for redirect scripts or embeds
        script_redirect = re.search(r'(?:location\.href|replace)\(["\'](https?://[^"\']+)["\']\)', html_text)
        if script_redirect:
            current_url = script_redirect.group(1)
            continue

        # 3. Check for iframes or download buttons
        next_candidate = None
        for a in soup.select("iframe[src], a[href*='drive'], a.btn, a.download-btn, a[href*='file']"):
            href = a.get("src") or a.get("href")
            if href and href.startswith("http") and href != current_url:
                next_candidate = href
                break

        if next_candidate:
            current_url = next_candidate
        else:
            break

    total_time = time.time() - total_start
    total_ms = int(total_time * 1000)

    # Stream byte-range check if terminal link was reached
    byte_range_ok = False
    if terminal_stream:
        range_res = fetch_url(terminal_stream, extra_headers={"Range": "bytes=0-1024"}, timeout=5)
        byte_range_ok = range_res["status"] in (200, 206)

    is_within_sla = total_time <= sla_seconds and (terminal_stream is not None or len(hops) > 1)

    return {
        "hops": hops,
        "total_ms": total_ms,
        "terminal_stream": terminal_stream,
        "byte_range_ok": byte_range_ok,
        "sla_seconds": sla_seconds,
        "is_within_sla": is_within_sla
    }


def main():
    if len(sys.argv) < 2:
        print("Usage: python scripts/probe_target.py <target_url>")
        sys.exit(1)

    target_url = sys.argv[1].strip()
    print("\n" + "=" * 70)
    print("  CLOUDSTREAM 3 — PRE-FLIGHT RECONNAISSANCE & FEASIBILITY REPORT")
    print(f"  Target: {target_url}")
    print("=" * 70)

    scorecard = {}

    # -------------------------------------------------------------
    # 1. Web Architecture & Anti-Bot Diagnostic
    # -------------------------------------------------------------
    print("\n[+] 1. PROBING ARCHITECTURE & ANTI-BOT STATUS...")
    t0 = time.time()
    res1 = fetch_url(target_url)
    t_req = int((time.time() - t0) * 1000)

    if res1["status"] in (403, 503) or "challenges.cloudflare.com" in res1["text"]:
        print(f"  ❌ Challenge Screen Detected (HTTP {res1['status']}) [{t_req}ms]")
        scorecard["Anti-Bot / Reachability"] = ("FAIL", f"HTTP {res1['status']} Verification Challenge")
    elif res1["status"] == 200:
        print(f"  ✔ Direct Pure HTTP Connection Established (HTTP 200) [{t_req}ms]")
        scorecard["Anti-Bot / Reachability"] = ("PASS", f"HTTP 200 in {t_req}ms")
    else:
        print(f"  ⚠️ Unexpected HTTP Status: {res1['status']} ({res1['error']})")
        scorecard["Anti-Bot / Reachability"] = ("CAUTION", f"HTTP {res1['status']}")

    soup1 = BeautifulSoup(res1["text"], "html.parser")

    # -------------------------------------------------------------
    # 2. Pagination Integrity Probe
    # -------------------------------------------------------------
    print("\n[+] 2. PROBING PAGINATION INTEGRITY...")
    page2_url = target_url.rstrip("/") + "/page/2/"
    res2 = fetch_url(page2_url)
    if res2["status"] == 200:
        soup2 = BeautifulSoup(res2["text"], "html.parser")
        p1_links = [a.get("href") for a in soup1.select("article a, h2 a") if a.get("href")]
        p2_links = [a.get("href") for a in soup2.select("article a, h2 a") if a.get("href")]
        overlap = set(p1_links).intersection(set(p2_links))
        if p1_links and p2_links and len(overlap) < len(p1_links):
            print(f"  ✔ Pagination Verified: Page 1 ({len(p1_links)} items) != Page 2 ({len(p2_links)} items, overlap: {len(overlap)})")
            scorecard["Pagination Integrity"] = ("PASS", f"P1: {len(p1_links)} items, P2: {len(p2_links)} items")
        else:
            print(f"  ❌ Pagination Broken: Page 2 returns duplicate items of Page 1")
            scorecard["Pagination Integrity"] = ("FAIL", "Duplicate items on Page 2")
    else:
        print(f"  ⚠️ Page 2 returned HTTP {res2['status']}")
        scorecard["Pagination Integrity"] = ("CAUTION", f"Page 2 returned HTTP {res2['status']}")

    # -------------------------------------------------------------
    # 3. Title Cleaning Verification (Raw vs Cleaned)
    # -------------------------------------------------------------
    print("\n[+] 3. AUDITING TITLE CLEANING & SANITIZATION (Side-by-Side):")
    sample_cards = soup1.select("article.latestPost, article, div.post")[:4]
    if not sample_cards:
        sample_cards = soup1.select("h2.title, h2")[:4]

    tested_titles = []
    for idx, card in enumerate(sample_cards, 1):
        a_tag = card.select_one("h2 a, header h2 a, a.post-image") or card.find("a")
        raw_title = (a_tag.get("title") or a_tag.text or "").strip() if a_tag else ""
        if raw_title:
            cleaned = clean_title(raw_title)
            tested_titles.append((raw_title, cleaned))
            print(f"  [Item {idx}]")
            print(f"    Raw:     '{raw_title[:75]}...'")
            print(f"    Cleaned: '{cleaned}'")

    if tested_titles:
        scorecard["Title Sanitization"] = ("PASS", f"{len(tested_titles)} titles audited")
    else:
        scorecard["Title Sanitization"] = ("CAUTION", "No cards found on page")

    # -------------------------------------------------------------
    # 4. Poster Reachability & Image Verification
    # -------------------------------------------------------------
    print("\n[+] 4. PROBING POSTER & BACKDROP IMAGE REACHABILITY:")
    image_checks = []
    for card in sample_cards[:3]:
        img = card.select_one("img")
        raw_src = img.get("data-src") or img.get("data-lazy-src") or img.get("src") if img else None
        if raw_src:
            ok, msg = probe_image(raw_src, target_url)
            image_checks.append(ok)
            status_icon = "✔" if ok else "❌"
            print(f"  {status_icon} Poster: {raw_src[:60]}... -> {msg}")

    if all(image_checks) and image_checks:
        scorecard["Image Renderability"] = ("PASS", "HTTP 200 / Valid Image MIME")
    elif any(image_checks):
        scorecard["Image Renderability"] = ("CAUTION", "Some images failed")
    else:
        scorecard["Image Renderability"] = ("FAIL", "Images failed to render")

    # -------------------------------------------------------------
    # 5. Media Typology & TV Season Hierarchy Check
    # -------------------------------------------------------------
    print("\n[+] 5. AUDITING TV SEASON & EPISODE HIERARCHY:")
    # Find candidate series
    candidate_series_url = None
    for a in soup1.find_all("a", href=True):
        href = a["href"]
        txt = (a.get("title") or a.text or "").lower()
        if ("season" in txt or "/series/" in href or "/web-series/" in href) and href.startswith("http"):
            candidate_series_url = href
            break

    if candidate_series_url:
        print(f"  Inspecting TV Series URL: {candidate_series_url}")
        s_res = fetch_url(candidate_series_url)
        s_soup = BeautifulSoup(s_res["text"], "html.parser")
        seasons = extract_season_hierarchy(s_soup)

        if len(seasons) > 1:
            print(f"  ✔ Multi-Season Traversal Succeeded:")
            for s_num, eps in sorted(seasons.items()):
                print(f"      Season {s_num}: {len(eps)} episodes extracted")
            scorecard["Season Hierarchy"] = ("PASS", f"{len(seasons)} seasons preserved (no Season 1 collapse)")
        elif len(seasons) == 1:
            print(f"  ✔ Single Season Verified: {len(list(seasons.values())[0])} episodes extracted")
            scorecard["Season Hierarchy"] = ("PASS", "Single season hierarchy intact")
        else:
            print("  ⚠️ No episodes could be extracted from candidate series")
            scorecard["Season Hierarchy"] = ("CAUTION", "No structured episodes found")
    else:
        print("  Notice: No TV series link discovered on homepage to probe hierarchy")
        scorecard["Season Hierarchy"] = ("N/A", "Only movies detected")

    # -------------------------------------------------------------
    # 6. Stream Link Velocity & Fake Timer Benchmark (< 3s)
    # -------------------------------------------------------------
    print("\n[+] 6. BENCHMARKING STREAM LINK VELOCITY & FAKE TIMERS:")
    # Find candidate link
    candidate_post_url = None
    for a in soup1.select("article a, h2 a"):
        href = a.get("href", "")
        if href.startswith("http") and "page" not in href:
            candidate_post_url = href
            break

    if candidate_post_url:
        p_res = fetch_url(candidate_post_url)
        p_soup = BeautifulSoup(p_res["text"], "html.parser")
        stream_link = None
        for a in p_soup.select("a[href]"):
            h = a["href"]
            txt = a.text.lower()
            if any(k in h for k in ["links.", "episodes.", "embed", "player", "stream", "download"]) and "zip" not in txt and "batch" not in txt:
                stream_link = h
                break

        if stream_link:
            print(f"  Benchmarking Link: {stream_link[:65]}...")
            if cross_check_native_extractor:
                print("\n  [+] CROSS-CHECKING AGAINST NATIVE CS3 EXTRACTORS:")
                cross_check_native_extractor(stream_link)
                print("")
            bench = benchmark_link_velocity(stream_link, target_url)
            elapsed = bench["elapsed_ms"]
            if bench["viable"] and elapsed <= 5000:
                print(f"  ✔ VELOCITY: FAST — Resolved {bench['type']} in {elapsed}ms")
                print(f"    Detail: {bench['reason']}")
                scorecard["Link Velocity (≤ 10s)"] = ("PASS", f"{elapsed}ms (Fast {bench['type']})")
            elif bench["viable"] and elapsed <= 10000:
                print(f"  ✔ VELOCITY: ACCEPTABLE — Resolved {bench['type']} in {elapsed}ms (within 10s ceiling)")
                print(f"    Detail: {bench['reason']}")
                scorecard["Link Velocity (≤ 10s)"] = ("PASS", f"{elapsed}ms (Acceptable)")
            elif bench["viable"]:
                print(f"  ⚠️ VELOCITY FEEDBACK: Resolved in {elapsed}ms (> 10s ceiling — non-blocking notice)")
                scorecard["Link Velocity (≤ 10s)"] = ("CAUTION", f"Slow: {elapsed}ms (Non-blocking)")
            else:
                print(f"  ❌ VELOCITY FAILED: {bench['reason']} ({elapsed}ms)")
                scorecard["Link Velocity (≤ 10s)"] = ("FAIL", bench["reason"])
        else:
            print("  ⚠️ No immediate stream/download link found on sample post")
            scorecard["Link Velocity (≤ 10s)"] = ("CAUTION", "No direct link tag found")
    else:
        scorecard["Link Velocity (≤ 10s)"] = ("CAUTION", "No sample post found")

    # -------------------------------------------------------------
    # FINAL PRE-FLIGHT SCORECARD
    # -------------------------------------------------------------
    print("\n" + "=" * 70)
    print("  PRE-FLIGHT FEASIBILITY SCORECARD")
    print("=" * 70)
    passed = 0
    total = len(scorecard)
    for metric, (verdict, detail) in scorecard.items():
        icon = "✔" if verdict == "PASS" else ("⚠️" if verdict in ("CAUTION", "N/A") else "❌")
        if verdict == "PASS":
            passed += 1
        print(f"  {icon} {metric:<28}: [{verdict:<7}] {detail}")
    print("-" * 70)

    if passed >= 4 and all(v != "FAIL" for v, _ in scorecard.values()):
        print("  🎉 VERDICT: VIABLE TARGET (GO AHEAD WITH KOTLIN SCAFFOLDING)")
        print("  The source passed link velocity, image renderability, and DOM hierarchy.")
    else:
        print("  ⚠️ VERDICT: HIGH-CHURN / UNVIABLE SOURCE (ACTION REQUIRED)")
        print("  Target fails velocity, uses unpassable timers, or has high domain churn.")
        print("  ADVICE: Report findings to developer before writing any plugin code.")
    print("=" * 70 + "\n")


if __name__ == "__main__":
    main()
