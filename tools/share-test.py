#!/usr/bin/env python3
"""
Test rig for phone-to-phone sharing.
Can act as both a receiver (downloading from phone) and a sender (serving to phone).

Usage:
  # Test receiver: connect to phone's ShareServer
  python3 share-test.py receive <phone-ip>

  # Test sender: run a fake ShareServer for phone to connect to
  python3 share-test.py send [--port 8432]

  # Just probe the server info
  python3 share-test.py info <phone-ip>

  # Download manifest only
  python3 share-test.py manifest <phone-ip>

  # Download a single file
  python3 share-test.py file <phone-ip> <path>

  # Full download test (downloads everything to ./share-test-data/)
  python3 share-test.py download <phone-ip> [--no-filters]
"""

import sys
import os
import json
import time
import http.client
import http.server
import threading
import socketserver
from pathlib import Path

PORT = 8432
TEST_DIR = Path("share-test-data")


def get_json(host, path, port=PORT):
    """GET a JSON endpoint."""
    conn = http.client.HTTPConnection(host, port, timeout=10)
    conn.request("GET", path)
    resp = conn.getresponse()
    body = resp.read().decode()
    if resp.status != 200:
        print(f"  HTTP {resp.status}: {body}")
        return None
    return json.loads(body)


def download_file(host, remote_path, local_path, port=PORT):
    """Download a single file with progress."""
    conn = http.client.HTTPConnection(host, port, timeout=60)
    conn.request("GET", f"/file/{remote_path}")
    resp = conn.getresponse()
    
    if resp.status != 200:
        print(f"  ERROR {resp.status} downloading {remote_path}")
        return False
    
    total = int(resp.getheader("Content-Length", 0))
    local_path.parent.mkdir(parents=True, exist_ok=True)
    
    downloaded = 0
    with open(local_path, "wb") as f:
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            f.write(chunk)
            downloaded += len(chunk)
            if total > 0:
                pct = (downloaded * 100) // total
                mb = downloaded / (1024 * 1024)
                total_mb = total / (1024 * 1024)
                print(f"\r  {remote_path}: {mb:.1f}/{total_mb:.1f} MB ({pct}%)", end="", flush=True)
    
    print()
    return True


def cmd_info(host):
    """Probe server info."""
    print(f"Connecting to {host}:{PORT}...")
    info = get_json(host, "/info")
    if info:
        print(f"  Version:    {info.get('version')}")
        print(f"  Height:     {info.get('chainHeight'):,}")
        print(f"  Filters:    {info.get('hasFilters')}")
        print(f"  Transfers:  {info.get('activeTransfers')}/{info.get('maxConcurrent')}")
    return info


def cmd_manifest(host):
    """Fetch and display manifest."""
    print(f"Fetching manifest from {host}:{PORT}...")
    manifest = get_json(host, "/manifest")
    if not manifest:
        return None
    
    files = manifest.get("files", [])
    total = manifest.get("totalSize", 0)
    
    print(f"\n  Files: {len(files)}")
    print(f"  Total: {total / (1024**3):.2f} GB\n")
    
    # Group by directory
    dirs = {}
    for f in files:
        path = f["path"]
        d = path.split("/")[0]
        if d not in dirs:
            dirs[d] = {"count": 0, "size": 0}
        dirs[d]["count"] += 1
        dirs[d]["size"] += f["size"]
    
    for d, info in sorted(dirs.items()):
        print(f"  {d}/: {info['count']} files, {info['size'] / (1024**2):.1f} MB")
    
    return manifest


def cmd_download(host, include_filters=True):
    """Full download test."""
    info = cmd_info(host)
    if not info:
        print("Cannot connect to server")
        return
    
    manifest = get_json(host, "/manifest")
    if not manifest:
        print("Cannot fetch manifest")
        return
    
    files = manifest.get("files", [])
    total_size = 0
    download_list = []
    
    for f in files:
        path = f["path"]
        size = f["size"]
        if not include_filters and path.startswith("indexes/"):
            continue
        download_list.append((path, size))
        total_size += size
    
    print(f"\nDownloading {len(download_list)} files ({total_size / (1024**3):.2f} GB)")
    print(f"Destination: {TEST_DIR.absolute()}\n")
    
    TEST_DIR.mkdir(exist_ok=True)
    
    start = time.time()
    downloaded_total = 0
    
    for i, (path, size) in enumerate(download_list):
        print(f"[{i+1}/{len(download_list)}]", end=" ")
        local = TEST_DIR / path
        if download_file(host, path, local):
            downloaded_total += size
    
    elapsed = time.time() - start
    speed = downloaded_total / elapsed / (1024 * 1024) if elapsed > 0 else 0
    
    print(f"\nDone! {downloaded_total / (1024**3):.2f} GB in {elapsed:.0f}s ({speed:.1f} MB/s)")


def cmd_file(host, path):
    """Download a single file."""
    local = TEST_DIR / path
    TEST_DIR.mkdir(exist_ok=True)
    print(f"Downloading {path}...")
    if download_file(host, path, local):
        print(f"  Saved to {local}")


def cmd_receive(host):
    """Quick receive test: info + manifest + download first 3 small files."""
    info = cmd_info(host)
    if not info:
        return
    
    print()
    manifest = cmd_manifest(host)
    if not manifest:
        return
    
    files = manifest.get("files", [])
    # Pick 3 smallest files for quick test
    small_files = sorted(files, key=lambda f: f["size"])[:3]
    
    print(f"\nQuick test: downloading {len(small_files)} smallest files...\n")
    TEST_DIR.mkdir(exist_ok=True)
    
    for f in small_files:
        path = f["path"]
        local = TEST_DIR / path
        download_file(host, path, local)
    
    print("\nReceive test passed!")
    
    # Test APK endpoint
    print("\nTesting /apk endpoint...")
    conn = http.client.HTTPConnection(host, PORT, timeout=10)
    conn.request("GET", "/apk")
    resp = conn.getresponse()
    size = int(resp.getheader("Content-Length", 0))
    resp.read()  # Consume body
    print(f"  APK available: {size / (1024*1024):.1f} MB")


class FakeShareHandler(http.server.BaseHTTPRequestHandler):
    """Minimal fake ShareServer for testing receiver side on phone."""
    
    data_dir = None  # Set before starting
    
    def do_GET(self):
        if self.path == "/info":
            self.send_json({
                "version": "test-rig",
                "chainHeight": 938000,
                "hasFilters": False,
                "maxConcurrent": 2,
                "activeTransfers": 0
            })
        elif self.path == "/manifest":
            files = []
            total = 0
            if self.data_dir and self.data_dir.exists():
                for f in self.data_dir.rglob("*"):
                    if f.is_file():
                        rel = str(f.relative_to(self.data_dir))
                        files.append({"path": rel, "size": f.stat().st_size})
                        total += f.stat().st_size
            self.send_json({"files": files, "totalSize": total, "fileCount": len(files)})
        elif self.path.startswith("/file/"):
            rel = self.path[6:]
            if self.data_dir:
                fpath = self.data_dir / rel
                if fpath.exists() and fpath.is_file():
                    self.send_response(200)
                    self.send_header("Content-Type", "application/octet-stream")
                    self.send_header("Content-Length", str(fpath.stat().st_size))
                    self.end_headers()
                    with open(fpath, "rb") as f:
                        while chunk := f.read(65536):
                            self.wfile.write(chunk)
                    return
            self.send_error(404)
        elif self.path == "/apk":
            self.send_response(200)
            fake = b"FAKE-APK-FOR-TESTING"
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(len(fake)))
            self.end_headers()
            self.wfile.write(fake)
        else:
            self.send_error(404)
    
    def send_json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    
    def log_message(self, format, *args):
        print(f"  [{self.client_address[0]}] {format % args}")


def cmd_send(port=PORT, data_dir=None):
    """Run a fake ShareServer for phone to test against."""
    if data_dir:
        FakeShareHandler.data_dir = Path(data_dir)
        print(f"Serving files from: {data_dir}")
    elif TEST_DIR.exists():
        FakeShareHandler.data_dir = TEST_DIR
        print(f"Serving previously downloaded files from: {TEST_DIR}")
    else:
        FakeShareHandler.data_dir = None
        print("No data directory — manifest will be empty")
        print(f"(Download first with: {sys.argv[0]} download <phone-ip>)")
    
    # Get local IP
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.0.0.1", 1))
        local_ip = s.getsockname()[0]
    except:
        local_ip = "127.0.0.1"
    finally:
        s.close()
    
    print(f"\nFake ShareServer running on {local_ip}:{port}")
    print(f"QR data: {json.dumps({'host': local_ip, 'port': port, 'chainHeight': 938000, 'hasFilters': False, 'version': 'test-rig'})}")
    print("Press Ctrl+C to stop\n")
    
    with socketserver.TCPServer(("0.0.0.0", port), FakeShareHandler) as httpd:
        httpd.serve_forever()


def usage():
    print(__doc__)
    sys.exit(1)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        usage()
    
    cmd = sys.argv[1]
    
    if cmd == "info" and len(sys.argv) >= 3:
        cmd_info(sys.argv[2])
    elif cmd == "manifest" and len(sys.argv) >= 3:
        cmd_manifest(sys.argv[2])
    elif cmd == "file" and len(sys.argv) >= 4:
        cmd_file(sys.argv[2], sys.argv[3])
    elif cmd == "receive" and len(sys.argv) >= 3:
        cmd_receive(sys.argv[2])
    elif cmd == "download" and len(sys.argv) >= 3:
        no_filters = "--no-filters" in sys.argv
        cmd_download(sys.argv[2], include_filters=not no_filters)
    elif cmd == "send":
        port = PORT
        data_dir = None
        for i, arg in enumerate(sys.argv[2:], 2):
            if arg == "--port" and i + 1 < len(sys.argv):
                port = int(sys.argv[i + 1])
            elif arg == "--data" and i + 1 < len(sys.argv):
                data_dir = sys.argv[i + 1]
        cmd_send(port, data_dir)
    else:
        usage()
