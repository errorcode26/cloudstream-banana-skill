#!/usr/bin/env python3
"""
CloudStream 3 — Real-Time Upstream Extractor Sync & Cross-Checker
================================================================
Synchronizes with the official recloudstream/cloudstream repository,
extracts all native file host extractors, indexes their domains,
and cross-checks target video URLs in real-time.

Usage:
  python scripts/sync_upstream_extractors.py --sync
  python scripts/sync_upstream_extractors.py --check <url>
"""

import sys
import os
import re
import json
import subprocess
import urllib.parse

REPO_URL = "https://github.com/recloudstream/cloudstream.git"
REPO_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "scratch", "cloudstream"))
INDEX_FILE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "scratch", "native_extractors.json"))


def ensure_upstream_synced():
    """Clones or pulls the latest recloudstream/cloudstream repo."""
    os.makedirs(os.path.dirname(REPO_DIR), exist_ok=True)
    if not os.path.exists(REPO_DIR):
        print(f"[*] Cloning latest recloudstream/cloudstream (depth 1)...")
        res = subprocess.run(
            ["git", "clone", "--depth", "1", REPO_URL, REPO_DIR],
            capture_output=True,
            text=True
        )
        if res.returncode != 0:
            print(f"[!] Git clone failed: {res.stderr}")
            return False
        print("[+] Upstream repository successfully cloned.")
    else:
        print(f"[*] Upstream repo found at {REPO_DIR}. Checking for updates...")
        res = subprocess.run(
            ["git", "-C", REPO_DIR, "pull", "--ff-only"],
            capture_output=True,
            text=True
        )
        if res.returncode == 0:
            print(f"[+] Git pull: {res.stdout.strip()}")
        else:
            print(f"[!] Git pull notice: {res.stderr.strip() or 'Working directory clean'}")
    return True


def parse_native_extractors():
    """Parses all Kotlin extractor classes from the library module."""
    extractors_dir = os.path.join(REPO_DIR, "library", "src", "commonMain", "kotlin", "com", "lagradost", "cloudstream3", "extractors")
    if not os.path.isdir(extractors_dir):
        print(f"[!] Extractor directory not found at {extractors_dir}")
        return []

    results = []
    seen = set()

    for root, _, files in os.walk(extractors_dir):
        for f in files:
            if not f.endswith(".kt"):
                continue
            fpath = os.path.join(root, f)
            with open(fpath, "r", encoding="utf-8", errors="replace") as fp:
                content = fp.read()

            # Regex to locate class declarations: class Name : Parent()
            class_blocks = re.split(r'\n(?=(?:open\s+)?class\s+)', content)
            for block in class_blocks:
                cls_match = re.search(r'(?:open\s+)?class\s+([A-Za-z0-9_]+)\s*(?::\s*([A-Za-z0-9_]+))?', block)
                if not cls_match:
                    continue

                cls_name = cls_match.group(1)
                parent_cls = cls_match.group(2) or "ExtractorApi"

                # Find mainUrl
                url_match = re.search(r'override\s+(?:val|var)\s+mainUrl\s*=\s*["\']([^"\']+)["\']', block)
                main_url = url_match.group(1) if url_match else None

                # Find name
                name_match = re.search(r'override\s+(?:val|var)\s+name\s*=\s*["\']([^"\']+)["\']', block)
                display_name = name_match.group(1) if name_match else cls_name

                # Extract domain if url exists
                domain = None
                if main_url:
                    try:
                        parsed = urllib.parse.urlparse(main_url)
                        domain = parsed.netloc.lower().replace("www.", "")
                    except Exception:
                        pass

                key = (cls_name, main_url)
                if key not in seen and (main_url or parent_cls != "ExtractorApi"):
                    seen.add(key)
                    results.append({
                        "class": cls_name,
                        "parent": parent_cls,
                        "name": display_name,
                        "mainUrl": main_url,
                        "domain": domain,
                        "file": os.path.relpath(fpath, REPO_DIR)
                    })

    # Save to JSON
    os.makedirs(os.path.dirname(INDEX_FILE), exist_ok=True)
    with open(INDEX_FILE, "w", encoding="utf-8") as fp:
        json.dump(results, fp, indent=2)

    print(f"[+] Successfully indexed {len(results)} native CloudStream extractors into {INDEX_FILE}")
    return results


def load_index():
    if not os.path.exists(INDEX_FILE):
        return parse_native_extractors()
    try:
        with open(INDEX_FILE, "r", encoding="utf-8") as fp:
            return json.load(fp)
    except Exception:
        return parse_native_extractors()


def check_url(url: str):
    """Cross-checks a candidate video embed/stream URL against CS3 native extractors."""
    index = load_index()
    if not index:
        print("[!] No native extractors indexed. Run --sync first.")
        return None

    try:
        parsed = urllib.parse.urlparse(url)
        netloc = parsed.netloc.lower().replace("www.", "")
    except Exception:
        netloc = url.lower()

    # 1. Exact or suffix domain match
    exact_matches = []
    for item in index:
        dom = item.get("domain")
        if dom and (dom == netloc or netloc.endswith("." + dom) or dom.endswith("." + netloc)):
            exact_matches.append(item)

    if exact_matches:
        match = exact_matches[0]
        print(f"\n[+] MATCH FOUND IN NATIVE CS3 EXTRACTORS:")
        print(f"    Class Name:   {match['class']}")
        print(f"    Parent Class: {match['parent']}")
        print(f"    Display Name: {match['name']}")
        print(f"    Main URL:     {match['mainUrl']}")
        print(f"    Defined In:   {match['file']}")
        print(f"\n[+] RECOMMENDATION: USE CS3 NATIVE EXTRACTOR")
        print(f"    Action: Pass this URL directly to loadExtractor(url, subtitleCallback, callback).")
        print(f"    Do NOT write custom extractor code unless live JVM testing fails.")
        return {"type": "EXACT", "match": match}

    # 2. Fuzzy / Keyword similarity (e.g. streamwish mirror)
    keyword_matches = []
    base_keyword = re.sub(r'\.(com|to|pro|xyz|top|org|net|site|click|live)$', '', netloc)
    for item in index:
        dom = item.get("domain") or ""
        item_keyword = re.sub(r'\.(com|to|pro|xyz|top|org|net|site|click|live)$', '', dom)
        if len(base_keyword) >= 5 and (base_keyword in item_keyword or item_keyword in base_keyword):
            keyword_matches.append(item)

    if keyword_matches:
        match = keyword_matches[0]
        print(f"\n[!] POTENTIAL MIRROR DETECTED OF NATIVE EXTRACTOR:")
        print(f"    Target Domain: {netloc}")
        print(f"    Matches Base:  {match['class']} (Domain: {match['domain']})")
        print(f"\n[+] RECOMMENDATION: RE-ALIAS NATIVE EXTRACTOR (RAPID ALIAS)")
        print(f"    Action: Subclass {match['class']} and override mainUrl to '{parsed.scheme}://{parsed.netloc}':")
        print(f"    class Custom{match['class']} : {match['class']}() {{")
        print(f"        override val name = \"{match['name']} Mirror\"")
        print(f"        override val mainUrl = \"{parsed.scheme}://{parsed.netloc}\"")
        print(f"    }}")
        print(f"    registerExtractorAPI(Custom{match['class']}())")
        return {"type": "MIRROR", "match": match}

    print(f"\n[-] NO NATIVE EXTRACTOR MATCH FOR: {netloc}")
    print(f"    Recommendation: Host uses proprietary API/player. Build a custom ExtractorApi subclass in <Plugin>Extractor.kt.")
    return {"type": "NONE", "match": None}


def main():
    if len(sys.argv) < 2 or sys.argv[1] in ("--help", "-h"):
        print("Usage:")
        print("  python scripts/sync_upstream_extractors.py --sync")
        print("  python scripts/sync_upstream_extractors.py --check <embed_url>")
        return

    cmd = sys.argv[1]
    if cmd == "--sync":
        ensure_upstream_synced()
        parse_native_extractors()
    elif cmd == "--check":
        if len(sys.argv) < 3:
            print("Please provide a URL to check: python scripts/sync_upstream_extractors.py --check <url>")
            return
        check_url(sys.argv[2])
    else:
        # Default treat as URL to check
        check_url(cmd)


if __name__ == "__main__":
    main()
