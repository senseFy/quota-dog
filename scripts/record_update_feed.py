#!/usr/bin/env python3
"""Record a verified public feed for audit; unchanged feeds create no commit."""
import base64
import json
from pathlib import Path
import subprocess
import tempfile
from publish_update import ROOT, fetch, properties, verify_feed

REPOSITORY = "senseFy/quota-dog"
RECORD = "infra/cloudflare/published/stable.json"


def main():
    config = properties(ROOT / "updater.properties")
    with tempfile.TemporaryDirectory(prefix="quotadog-public-feed-") as directory:
        feed = Path(directory) / "stable.json"
        if not fetch(config["baseUrl"] + "/feeds/stable.json", feed):
            print("No published feed to record.")
            return
        data = feed.read_bytes()
        payload = verify_feed(data, config)
        if (payload["schema"], payload["appId"], payload["channel"]) != (1, "saien.quotadog", "stable"):
            raise RuntimeError("Unexpected feed scope")
        endpoint = f"repos/{REPOSITORY}/contents/{RECORD}"
        # The initial audit file is committed with this script, so a missing file
        # is unexpected and must not be confused with an authentication failure.
        existing = json.loads(subprocess.check_output(["gh", "api", endpoint + "?ref=main"]))
        if base64.b64decode(existing["content"]) == data:
            print("Signed feed is unchanged; no audit commit needed.")
            return
        previous = verify_feed(base64.b64decode(existing["content"]), config)
        if payload["sequence"] <= previous["sequence"]:
            raise RuntimeError("Refusing to record an older or conflicting feed")
        body = dict(message="Record signed update feed renewal [skip ci]", branch="main",
                    sha=existing["sha"], content=base64.b64encode(data).decode())
        result = subprocess.check_output(["gh", "api", "--method", "PUT", endpoint, "--input", "-"],
                                         input=json.dumps(body).encode())
        print("Recorded public feed:", json.loads(result)["commit"]["html_url"])


if __name__ == "__main__":
    main()
