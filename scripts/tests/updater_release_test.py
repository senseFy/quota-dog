#!/usr/bin/env python3
import base64
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_update import sign_feed, verify_feed
from updater_tools import tool


class PublisherTests(unittest.TestCase):
    def test_real_signatures_reject_tampering_and_the_wrong_private_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = root / "release.private-key"
            public = subprocess.check_output([str(tool("publisher")), "keygen", str(private)], text=True).strip()
            config = dict(keyId="test-key", publicKey=public)
            now = int(time.time())
            payload = dict(schema=1, appId="saien.quotadog", channel="stable", sequence=1,
                           issuedAt=now, expiresAt=now + 3600, releases=[])
            feed = root / "feed.json"
            sign_feed(payload, feed, config, private)
            self.assertEqual(verify_feed(feed.read_bytes(), config), payload)
            envelope = json.loads(feed.read_bytes())
            altered = dict(payload, appId="other.app")
            envelope["payload"] = base64.b64encode(json.dumps(altered).encode()).decode()
            with self.assertRaises(subprocess.CalledProcessError):
                verify_feed(json.dumps(envelope).encode(), config)
            other = root / "other.private-key"
            subprocess.check_output([str(tool("publisher")), "keygen", str(other)])
            with self.assertRaises(subprocess.CalledProcessError):
                sign_feed(payload, root / "wrong-key.json", config, other)


if __name__ == "__main__": unittest.main()
