#!/usr/bin/env python3
"""
CloudStream 3 — Universal Local Plugin Hosting Server
=====================================================
Hosts CloudStream 3 (.cs3) plugins on your local Wi-Fi network so you can
test, install, and update them directly on your Android phone/TV/emulator.

Solves the critical CloudStream pitfalls:
  1. No fileHash field (prevents Kotlinx UnknownKeyException).
  2. No repositoryUrl field (prevents repo URL mismatch filter drops).
  3. No .ico URLs (prevents Android BitmapFactory crashes; uses PNG).
  4. Normalized tvTypes (only standard CloudStream top-level categories).
  5. Strips conditional caching headers (never returns HTTP 304, forces fresh reload).
  6. Automatic Wi-Fi LAN IP detection (filters out VMware, WSL, Docker virtual adapters).
  7. Hot-reload / file watching (auto-refreshes repo whenever gradle rebuilds a .cs3).

Usage:
  python serve_cs3.py
  python serve_cs3.py --port 8080
  python serve_cs3.py --scan-dir ./my_project
  python serve_cs3.py --ip 192.168.1.50
"""

import os
import sys
import time
import json
import socket
import shutil
import zipfile
import argparse
import threading
from pathlib import Path
from http.server import HTTPServer, SimpleHTTPRequestHandler

# Default fallback icon (Google Material Design Movie PNG)
FALLBACK_ICON = "https://raw.githubusercontent.com/google/material-design-icons/master/png/av/movie/materialicons/48dp/2x/baseline_movie_black_48dp.png"

# CloudStream recognized top-level categories
VALID_TV_TYPES = {"Movie", "TvSeries", "Anime", "Others", "Live", "Cartoon"}


def get_lan_ip(manual_ip=None) -> str:
    """
    Detect the active Wi-Fi / Ethernet LAN IP address.
    Prioritizes physical LAN subnets (192.168.x.x, 10.x.x.x) and
    strictly excludes virtual adapters (VMware, VirtualBox, WSL 172.16-31.x, Docker, Loopback).
    """
    if manual_ip:
        return manual_ip

    virtual_prefixes = (
        "127.", "169.254.", "172.16.", "172.17.", "172.18.", "172.19.", "172.20.",
        "172.21.", "172.22.", "172.23.", "172.24.", "172.25.", "172.26.",
        "172.27.", "172.28.", "172.29.", "172.30.", "172.31.",
        "192.168.229.", "192.168.48.", "192.168.56."  # Common VMware/VBox subnets
    )

    # Strategy 1: Look through system hostnames for true home/office LAN subnets
    try:
        hostname = socket.gethostname()
        all_ips = socket.gethostbyname_ex(hostname)[2]

        # Prioritize 192.168.31.x (common Xiaomi/OpenWrt router) or any non-virtual 192.168.x.x
        for ip in all_ips:
            if ip.startswith("192.168.") and not any(ip.startswith(v) for v in virtual_prefixes):
                return ip

        # Next check standard 10.x subnets
        for ip in all_ips:
            if ip.startswith("10.") and not any(ip.startswith(v) for v in virtual_prefixes):
                return ip
    except Exception:
        pass

    # Strategy 2: Open a UDP dummy socket to an external DNS
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            if ip and not any(ip.startswith(v) for v in virtual_prefixes):
                return ip
    except Exception:
        pass

    return "127.0.0.1"


def parse_gradle_metadata(plugin_dir: Path) -> dict:
    """
    Attempts to read metadata (description, authors, iconUrl, tvTypes, lang)
    from build.gradle.kts or build.gradle if present.
    """
    info = {
        "description": "",
        "authors": ["PluginAuthor"],
        "iconUrl": FALLBACK_ICON,
        "tvTypes": ["Others"],
        "language": "en"
    }

    for fname in ("build.gradle.kts", "build.gradle"):
        gradle_file = plugin_dir / fname
        if not gradle_file.exists():
            continue

        try:
            content = gradle_file.read_text(encoding="utf-8", errors="ignore")
            for line in content.splitlines():
                line = line.strip()
                if "description" in line and "=" in line:
                    info["description"] = line.split("=", 1)[1].strip().strip('"\'')
                elif "language" in line and "=" in line:
                    info["language"] = line.split("=", 1)[1].strip().strip('"\'')
                elif "iconUrl" in line and "=" in line:
                    raw_icon = line.split("=", 1)[1].strip().strip('"\'')
                    if raw_icon.startswith("http") and not raw_icon.endswith(".ico"):
                        info["iconUrl"] = raw_icon
                elif "authors" in line and "listOf" in line:
                    raw_auth = line.split("listOf", 1)[1].strip("() \t")
                    authors = [a.strip('"\' ') for a in raw_auth.split(",") if a.strip('"\' ')]
                    if authors:
                        info["authors"] = authors
                elif "tvTypes" in line and "listOf" in line:
                    raw_types = line.split("listOf", 1)[1].strip("() \t")
                    parsed = [t.strip('"\' ') for t in raw_types.split(",") if t.strip('"\' ')]
                    valid = [t for t in parsed if t in VALID_TV_TYPES]
                    if valid:
                        info["tvTypes"] = valid
            break
        except Exception:
            pass

    return info


def inspect_cs3_file(cs3_path: Path) -> dict:
    """
    Extracts manifest.json from a .cs3 zip package and pairs it with Gradle metadata.
    Returns a complete, CloudStream-compliant plugin dictionary.
    """
    manifest = {}
    try:
        with zipfile.ZipFile(cs3_path, "r") as z:
            if "manifest.json" in z.namelist():
                manifest = json.loads(z.read("manifest.json").decode("utf-8"))
    except Exception as e:
        print(f"  [!] Warning: Could not read manifest in {cs3_path.name}: {e}")

    stem_name = cs3_path.stem
    name = manifest.get("name") or stem_name
    version = int(manifest.get("version", 1))

    # Look for parent project directory for build.gradle.kts
    # Typically: <project_root>/<module>/build/<module>.cs3 -> module dir is parent.parent
    module_dir = cs3_path.parent.parent if cs3_path.parent.name == "build" else cs3_path.parent
    if not (module_dir / "build.gradle.kts").exists():
        candidate = cs3_path.resolve().parent
        found = False
        while candidate != candidate.parent:
            if (candidate / name / "build.gradle.kts").exists():
                module_dir = candidate / name
                found = True
                break
            try:
                for sub in candidate.iterdir():
                    if sub.is_dir() and (sub / name / "build.gradle.kts").exists():
                        module_dir = sub / name
                        found = True
                        break
            except Exception:
                pass
            if found:
                break
            candidate = candidate.parent

    gradle_meta = parse_gradle_metadata(module_dir)

    description = gradle_meta["description"] or f"{name} plugin for CloudStream."

    return {
        "name": name,
        "internalName": name,
        "description": description,
        "status": 1,
        "version": version,
        "apiVersion": 1,
        "authors": gradle_meta["authors"],
        "tvTypes": gradle_meta["tvTypes"],
        "language": gradle_meta["language"],
        "iconUrl": gradle_meta["iconUrl"],
        "fileSize": cs3_path.stat().st_size,
        "_local_path": cs3_path,
        "_filename": cs3_path.name,
    }


def find_all_cs3_files(root_dir: Path) -> list:
    """
    Recursively scans directory for .cs3 files, ignoring intermediate cache folders.
    """
    found = []
    ignored_subdirs = {
        "intermediates", "tmp", ".gradle", ".system_generated", "scratch",
        ".git", "_served_repo", "dist", "releases"
    }

    for p in root_dir.rglob("*.cs3"):
        if any(part in ignored_subdirs for part in p.parts):
            continue
        found.append(p)
    return found


class RepoBuilder:
    def __init__(self, scan_dir: Path, serve_dir: Path, base_url: str):
        self.scan_dir = scan_dir
        self.serve_dir = serve_dir
        self.base_url = base_url
        self.last_hashes = {}

    def build_repo(self) -> list:
        """
        Scans for .cs3 files, copies them into serve_dir, and generates
        compliant plugins.json and repo.json.
        """
        self.serve_dir.mkdir(parents=True, exist_ok=True)
        raw_cs3_files = find_all_cs3_files(self.scan_dir)

        # Deduplicate by filename, keeping the newest file
        newest_by_name = {}
        for f in raw_cs3_files:
            if f.name not in newest_by_name or f.stat().st_mtime > newest_by_name[f.name].stat().st_mtime:
                newest_by_name[f.name] = f
        cs3_files = list(newest_by_name.values())

        plugins_list = []
        for cs3 in cs3_files:
            meta = inspect_cs3_file(cs3)
            dest_file = self.serve_dir / cs3.name

            try:
                shutil.copy2(cs3, dest_file)
            except Exception as e:
                print(f"  [!] Failed to copy {cs3.name}: {e}")
                continue

            # CRITICAL RULES:
            # 1. DO NOT ADD 'fileHash' (causes Kotlinx UnknownKeyException)
            # 2. DO NOT ADD 'repositoryUrl' (causes repo URL filter mismatch)
            plugin_entry = {
                "name": meta["name"],
                "internalName": meta["internalName"],
                "description": meta["description"],
                "status": meta["status"],
                "version": meta["version"],
                "apiVersion": meta["apiVersion"],
                "authors": meta["authors"],
                "url": f"{self.base_url}/{cs3.name}",
                "tvTypes": meta["tvTypes"],
                "language": meta["language"],
                "iconUrl": meta["iconUrl"],
                "fileSize": dest_file.stat().st_size
            }
            plugins_list.append(plugin_entry)

        # Write plugins.json
        plugins_json_path = self.serve_dir / "plugins.json"
        with open(plugins_json_path, "w", encoding="utf-8") as f:
            json.dump(plugins_list, f, indent=2)

        # Write repo.json
        repo_json_path = self.serve_dir / "repo.json"
        icon_candidates = [
            self.scan_dir / "logo.png",
            self.scan_dir / "icon.png"
        ]
        for p in self.scan_dir.glob("*/logo.png"):
            icon_candidates.append(p)
        for p in self.scan_dir.glob("*/icon.png"):
            icon_candidates.append(p)
        icon_path = next((p for p in icon_candidates if p.exists()), None)

        icon_url = None
        if icon_path and icon_path.exists():
            try:
                shutil.copy2(icon_path, self.serve_dir / "icon.png")
                icon_url = f"{self.base_url}/icon.png"
            except Exception:
                pass

        repo_data = {
            "name": "Local Dev Repository",
            "description": "Local test repository for CloudStream 3 plugins",
            "manifestVersion": 1,
            "pluginLists": [
                f"{self.base_url}/plugins.json"
            ]
        }
        if icon_url:
            repo_data["iconUrl"] = icon_url

        with open(repo_json_path, "w", encoding="utf-8") as f:
            json.dump(repo_data, f, indent=2)

        return plugins_list

    def check_for_changes(self) -> bool:
        """Returns True if any .cs3 file was modified or created."""
        current_files = find_all_cs3_files(self.scan_dir)
        new_hashes = {}
        for f in current_files:
            try:
                new_hashes[str(f)] = f.stat().st_mtime
            except Exception:
                pass

        if new_hashes != self.last_hashes:
            self.last_hashes = new_hashes
            return True
        return False


class CloudStreamHTTPHandler(SimpleHTTPRequestHandler):
    """
    HTTP handler optimized for CloudStream 3:
      - Forces 200 OK (replaces 304 Not Modified to defeat cache lockup).
      - Strips conditional headers (If-Modified-Since, If-None-Match).
      - Sets aggressive no-cache headers.
      - Enables full CORS.
      - Provides correct MIME types.
    """

    def guess_type(self, path):
        if path.endswith(".json"):
            return "application/json; charset=utf-8"
        if path.endswith(".cs3"):
            return "application/octet-stream"
        return super().guess_type(path)

    def end_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        self.send_header("Pragma", "no-cache")
        self.send_header("Expires", "0")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(200)
        self.end_headers()

    def send_response(self, code, message=None):
        if code == 304:
            code = 200
        super().send_response(code, message)

    def do_GET(self):
        if hasattr(self, "headers") and self.headers is not None:
            self.headers._headers = [
                (k, v) for k, v in self.headers._headers
                if k.lower() not in ("if-modified-since", "if-none-match")
            ]
        super().do_GET()

    def log_message(self, fmt, *args):
        req_line = args[0] if args else ""
        status_code = args[1] if len(args) > 1 else ""
        now = time.strftime("%H:%M:%S")

        if "repo.json" in req_line:
            print(f"  [{now}] REPO FETCH    : CloudStream queried repository manifest (repo.json) [{status_code}]")
        elif "plugins.json" in req_line:
            print(f"  [{now}] PLUGIN LIST   : CloudStream retrieved plugin list (plugins.json) [{status_code}]")
        elif ".cs3" in req_line:
            plugin_name = req_line.split(".cs3")[0].split("/")[-1] + ".cs3"
            print(f"  [{now}] DOWNLOAD      : CloudStream downloaded binary ({plugin_name}) [{status_code}]")
        else:
            print(f"  [{now}] HTTP {status_code} : {req_line}")


def watch_loop(builder: RepoBuilder, interval: float = 3.0):
    """Background thread watching for Gradle rebuilds to update repo on the fly."""
    while True:
        time.sleep(interval)
        try:
            if builder.check_for_changes():
                print("\n  [*] Detected changes in .cs3 files! Rebuilding repository files...")
                plugins = builder.build_repo()
                print(f"  [*] Auto-updated repository with {len(plugins)} plugin(s).\n")
        except Exception as e:
            print(f"  [!] Error during auto-refresh: {e}")


def main():
    parser = argparse.ArgumentParser(description="Universal CloudStream 3 Local Plugin Hoster")
    parser.add_argument("--port", "-p", type=int, default=8080, help="Port to bind (default: 8080)")
    parser.add_argument("--ip", type=str, default=None, help="Force specific LAN IP address")
    parser.add_argument("--scan-dir", "-d", type=str, default=".", help="Root directory to scan for .cs3 files (default: .)")
    parser.add_argument("--no-watch", action="store_true", help="Disable hot-reload file watching")
    args = parser.parse_args()

    scan_path = Path(args.scan_dir).resolve()
    serve_path = scan_path / "_served_repo"

    lan_ip = get_lan_ip(args.ip)
    base_url = f"http://{lan_ip}:{args.port}"

    print(f"\nScanning for .cs3 plugins in: {scan_path}...")
    builder = RepoBuilder(scan_path, serve_path, base_url)
    plugins = builder.build_repo()
    builder.check_for_changes()

    os.chdir(serve_path)

    if not args.no_watch:
        t = threading.Thread(target=watch_loop, args=(builder,), daemon=True)
        t.start()

    border = "=" * 66
    print(f"\n{border}")
    print("  CLOUDSTREAM 3 — LOCAL REPOSITORY SERVER")
    print(f"{border}")
    print(f"  Host IP        : {lan_ip}")
    print(f"  Port           : {args.port}")
    print(f"  Base URL       : {base_url}")
    print(f"  Watched Dir    : {scan_path}")
    print(f"{'-' * 66}")
    print(f"  HOSTED PLUGINS ({len(plugins)}):")
    if not plugins:
        print("    (No .cs3 files found yet. Run './gradlew make' to build your plugin!)")
        print("    The server will automatically detect and host it once built.")
    else:
        for p in plugins:
            size_kb = round(p['fileSize'] / 1024, 1)
            print(f"    - {p['name']} (v{p['version']}) — {size_kb} KB")
            print(f"      Direct link: {p['url']}")

    print(f"{'-' * 66}")
    print("  ADD THIS REPOSITORY URL TO CLOUDSTREAM:")
    print(f"  {base_url}/repo.json")
    print(f"{border}\n")
    print("Server is active. Press Ctrl+C to stop.\n", flush=True)

    try:
        server = HTTPServer(("0.0.0.0", args.port), CloudStreamHTTPHandler)
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping local repository server.")
    except Exception as e:
        print(f"\nServer error: {e}")
        sys.exit(1)


if __name__ == "__main__":
    main()
