#!/usr/bin/env python3
"""Check a portable export zip against what the Android client requires.

The CI job that builds the portable export (the Java-25 exporter tables the
phone cannot run) packs the prepared root minus what the phone already made
from the player's own .minecraft (the client JAR's assets/ and data/, which
would overwrite the real sounds and title panorama with the JAR's
placeholders) and what is only build noise (*.log, the compiled exporters'
classes/). This script fails the build when anything the Android client
hard-requires at play time (REQUIRED_FILES in
crates/horizon-android/src/prepare.rs — parsed from that file, so it cannot
drift) is missing from the zip, or when an excluded path leaked back in.

    python3 scripts/verify-portable-export.py portable-export-26.3.zip
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import zipfile

PREPARE_RS = "crates/horizon-android/src/prepare.rs"
REQUIRED_RE = re.compile(r'pub const REQUIRED_FILES: &\[&str\] = &\[(.*?)\];', re.S)
ENTRY_RE = re.compile(r'"([^"]+)"')
MANIFEST = "horizon-resources.json"

# Never in the export: the phone's own preparation made these from the
# player's .minecraft, and the JAR's placeholders would overwrite the real
# sounds, panorama, Unifont, packs and languages (see prepare.rs: the file
# steps run on device). The logs and compiled classes are build noise.
EXCLUDED_PREFIXES = ("assets/", "data/", "classes/", "client-classes/")
EXCLUDED_SUFFIXES = (".log",)


def required_files(source: str) -> list[str]:
    """REQUIRED_FILES as written in prepare.rs, in order."""
    match = REQUIRED_RE.search(source)
    if not match:
        raise SystemExit(f"verify-portable-export: error: no REQUIRED_FILES in {PREPARE_RS}")
    return ENTRY_RE.findall(match.group(1))


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify a portable export zip for Android")
    parser.add_argument("zip", help="the portable export zip CI packed")
    args = parser.parse_args()

    names: set[str] = set()
    with zipfile.ZipFile(args.zip) as archive:
        roots = {name.split("/", 1)[0] for name in archive.namelist() if "/" in name}
        if len(roots) != 1:
            raise SystemExit(
                f"verify-portable-export: error: the export wraps {sorted(roots)}; expected one "
                "top-level folder (the prepared root)")
        prefix = f"{roots.pop()}/"
        for name in archive.namelist():
            relative = name[len(prefix):] if name.startswith(prefix) else name
            if relative:
                names.add(relative)

    problems: list[str] = []
    if MANIFEST not in names:
        problems.append(f"{MANIFEST} is missing: the import cannot find the prepared root")
    for required in required_files(open(PREPARE_RS).read()):
        if required not in names:
            problems.append(f"required file missing: {required}")
    for name in sorted(names):
        if name.endswith("/"):
            continue
        if any(name.startswith(prefix) or name == prefix.rstrip("/") for prefix in EXCLUDED_PREFIXES):
            problems.append(f"excluded path leaked into the export: {name}")
        elif name.endswith(EXCLUDED_SUFFIXES):
            problems.append(f"excluded path leaked into the export: {name}")

    if problems:
        for problem in problems:
            print(f"::error::{problem}" if os.environ.get("GITHUB_ACTIONS")
                  else f"error: {problem}", file=sys.stderr)
        return 1

    with zipfile.ZipFile(args.zip) as archive:
        manifest_name = next(n for n in archive.namelist() if n.endswith(MANIFEST))
        manifest = json.loads(archive.read(manifest_name))
    print(f"portable export ok: {len(names)} entries, Minecraft "
          f"{manifest.get('minecraft')}, schema {manifest.get('schema')}, "
          f"world_version {manifest.get('data_version')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
