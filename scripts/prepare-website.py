#!/usr/bin/env python3
"""Rewrite the download page for the release being prepared.

Runs inside releasekit's preparation commands, after version.properties has
already been bumped, so it reads the new version and build from that file.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "infra/cloudflare/public/index.html"


def update_site(text, version, build):
    text, count = re.subn(
        r'(<p id="macos-version">).*?(</p>)',
        rf'\g<1>Version {version} for Apple Silicon. Requires macOS 13 or later.\2',
        text)
    if count != 1:
        raise RuntimeError("Expected exactly one macos-version paragraph in the website")
    path = f"/updates/releases/{build}/QuotaDog-{version}-macos-aarch64.dmg"
    text, count = re.subn(r'(<a id="macos-download" href=")[^"]+("[^>]*>)',
                          rf'\g<1>{path}\2', text)
    if count != 1:
        raise RuntimeError("Expected exactly one macos-download link in the website")
    return text


def main():
    props = dict(
        line.split("=", 1)
        for line in (ROOT / "version.properties").read_text(encoding="utf-8").splitlines()
        if "=" in line)
    version, build = props["VERSION_NAME"].strip(), props["VERSION_CODE"].strip()
    SITE.write_text(update_site(SITE.read_text(encoding="utf-8"), version, build),
                    encoding="utf-8")
    print(f"Updated download page for {version} ({build})")


if __name__ == "__main__":
    main()
