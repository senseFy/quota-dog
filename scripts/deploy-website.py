#!/usr/bin/env python3
"""Post-publish hook: deploy the download site and record the public feed.

Runs after releasekit has published the installer and the signed feed. The
deployed page must be byte-identical to the prepared file, and the audit step
commits the verified public feed back to main (a mutable_paths change).
"""
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

from publish_update import fetch, properties

SITE = ROOT / "infra/cloudflare/public/index.html"


def main():
    subprocess.run(["wrangler", "deploy", "--config", "infra/cloudflare/wrangler.jsonc"],
                   cwd=ROOT, check=True)
    config = properties(ROOT / "updater.properties")
    origin = config["baseUrl"].removesuffix("/updates")
    with tempfile.TemporaryDirectory(prefix="quotadog-website-") as temporary:
        page = Path(temporary) / "index.html"
        if not fetch(origin + "/", page, maximum=1048576) \
                or page.read_bytes() != SITE.read_bytes():
            raise RuntimeError("Deployed website does not match the prepared download page")
    subprocess.run([sys.executable, str(ROOT / "scripts/record_update_feed.py")],
                   cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
