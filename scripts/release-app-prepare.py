#!/usr/bin/env python3
"""Post-build transforms on the Gradle output app, before releasekit signs it.

Currently trims the foreign Skiko architecture; the rules live in
sign_macos_app.py so the manual `make release-app` path shares them.
"""
import plistlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

from sign_macos_app import prune_foreign_skiko

APP = ROOT / "composeApp/build/compose/binaries/main-release/app/QuotaDog.app"


def main():
    info = plistlib.loads((APP / "Contents/Info.plist").read_bytes())
    prune_foreign_skiko(APP, APP / "Contents/MacOS" / info["CFBundleExecutable"])
    print("Pruned foreign Skiko architecture")


if __name__ == "__main__":
    main()
