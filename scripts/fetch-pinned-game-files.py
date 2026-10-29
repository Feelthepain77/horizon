#!/usr/bin/env python3
"""Fetch the pinned Minecraft Java Edition files from Mojang's public servers.

The Android port's CI builds the one-time portable export (the Java-25 exporter
tables the phone cannot run) by preparing on a runner, and a runner owns no
`.minecraft`. This script makes the minimal one horizon-prepare imports: the
pinned version JSON, the client jar, the server jar and the client libraries,
downloaded from the same public piston-meta/piston-data endpoints every
launcher uses and checked against research-lock.json — the same checks the
preparer applies again on import, so a mismatch cannot pass silently.

Nothing here is ever committed: the tree is written outside the checkout (the
caller passes --out), .gitignore keeps .local/ and *.jar out of the way, and
tools/check_public_tree.py guards the history. The exported tables the game
plays from are derived data, produced once per push and thrown away with the
runner.

    python3 scripts/fetch-pinned-game-files.py --out /tmp/minecraft
    horizon-prepare --prepare --minecraft-dir /tmp/minecraft \
        --server-jar /tmp/minecraft/server-<version>.jar
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
import urllib.request
from pathlib import Path, PurePosixPath

PISTON_PACKAGE = "https://piston-meta.mojang.com/v1/packages"
ATTEMPTS = 3


def fail(message: str) -> None:
    print(f"fetch-pinned-game-files: error: {message}", file=sys.stderr)
    raise SystemExit(1)


def download(url: str, destination: Path, sha1: str, what: str) -> None:
    """Fetch `url` into `destination`, checking its SHA-1 on the way."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, ATTEMPTS + 1):
        try:
            digest = hashlib.sha1()
            with urllib.request.urlopen(url, timeout=120) as response, \
                    destination.open("wb") as out:
                while True:
                    chunk = response.read(1 << 20)
                    if not chunk:
                        break
                    digest.update(chunk)
                    out.write(chunk)
            if digest.hexdigest() != sha1:
                destination.unlink(missing_ok=True)
                raise OSError(f"sha1 {digest.hexdigest()}, expected {sha1}")
            size = destination.stat().st_size
            print(f"  {what}: {size / 1e6:.1f} MB, sha1 ok")
            return
        except OSError as error:
            destination.unlink(missing_ok=True)
            if attempt == ATTEMPTS:
                fail(f"downloading {what} from {url} failed: {error}")
            print(f"  {what}: {error}; retrying ({attempt}/{ATTEMPTS})", file=sys.stderr)
            time.sleep(5 * attempt)


def safe_relative(path: str) -> PurePosixPath | None:
    """A relative path with no `..` and no backslashes, as the preparer accepts."""
    if "\\" in path or path.startswith("/"):
        return None
    parts = [part for part in path.split("/") if part and part != "."]
    if not parts or ".." in parts:
        return None
    return PurePosixPath(*parts)


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch the pinned game files for a CI preparation")
    parser.add_argument("--lock", type=Path, default=Path("research-lock.json"),
                        help="research-lock.json to pin against (default: beside the current folder)")
    parser.add_argument("--out", type=Path, required=True,
                        help="the .minecraft-shaped folder to write (keep it outside the checkout)")
    args = parser.parse_args()

    try:
        lock = json.loads(args.lock.read_text())
    except (OSError, ValueError) as error:
        fail(f"reading {args.lock}: {error}")
    game = lock.get("minecraft", {})
    version = game.get("version")
    java_major = game.get("java_major")
    manifest_sha1 = game.get("manifest_sha1")
    accepted = [manifest_sha1, *game.get("accepted_manifest_sha1", [])]
    client_sha1 = game.get("client_sha1")
    server_sha1 = game.get("server_sha1")
    if not all(isinstance(value, str) and value for value in
               (version, manifest_sha1, client_sha1, server_sha1)) or not isinstance(java_major, int):
        fail(f"{args.lock} does not pin a Minecraft version as horizon-prepare needs it")

    print(f"Pinned Minecraft {version} (Java {java_major})")
    version_dir = args.out / "versions" / version
    print(f"Version JSON from {PISTON_PACKAGE}/{manifest_sha1}/{version}.json")
    manifest_path = version_dir / f"{version}.json"
    download(f"{PISTON_PACKAGE}/{manifest_sha1}/{version}.json", manifest_path,
             manifest_sha1, "version JSON")
    manifest = json.loads(manifest_path.read_text())

    # The preparer applies these checks on import; a pinned mismatch is a
    # research-lock.json problem and must fail here, loudly, not there.
    if manifest.get("id") != version:
        fail(f"the version JSON describes {manifest.get('id')!r}, not {version}")
    if manifest.get("javaVersion", {}).get("majorVersion") != java_major:
        fail(f"the version JSON wants Java {manifest.get('javaVersion', {}).get('majorVersion')}")
    downloads = manifest.get("downloads", {})
    for side, pinned in (("client", client_sha1), ("server", server_sha1)):
        if downloads.get(side, {}).get("sha1") != pinned:
            fail(f"the version JSON's {side} jar does not match research-lock.json")
    if "client_mappings" in downloads or "server_mappings" in downloads:
        fail("the pinned version ships obfuscation mappings, which preparing refuses")

    download(downloads["client"]["url"], version_dir / f"{version}.jar",
             client_sha1, "client jar")
    download(downloads["server"]["url"], args.out / f"server-{version}.jar",
             server_sha1, "server jar")

    libraries = 0
    for library in manifest.get("libraries", []):
        artifact = library.get("downloads", {}).get("artifact")
        if not artifact:
            continue
        relative = safe_relative(artifact.get("path", ""))
        if relative is None:
            fail(f"unsafe library path for {library.get('name')}: {artifact.get('path')!r}")
        # Every platform's libraries, not just the runner's: the preparer
        # imports the ones its rules select and never reads the rest.
        download(artifact["url"], args.out / "libraries" / relative,
                 artifact["sha1"], f"library {library.get('name')}")
        libraries += 1

    print(f"Fetched {version}: 2 jars, {libraries} libraries, no assets "
          "(the export does not need the asset store)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
