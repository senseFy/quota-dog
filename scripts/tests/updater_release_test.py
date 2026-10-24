#!/usr/bin/env python3
import base64
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from publish_update import sign_feed, verify_feed
from updater_tools import tool
import publish_update


class PublisherTests(unittest.TestCase):
    def test_scheduled_renewal_preserves_releases_and_rejects_tampering(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = root / "release.private-key"
            public = subprocess.check_output([str(tool("publisher")), "keygen", str(private)], text=True).strip()
            config = dict(keyId="test-key", publicKey=public, baseUrl="https://updates.example.test/updates")
            (root / "updater.properties").write_text("\n".join(f"{key}={value}" for key, value in config.items()))
            now = int(time.time())
            release = dict(sequence=24, version="1.0.12", minimumInstalledSequence=0, notes={},
                           artifacts=[dict(target="macos-aarch64", kind="dmg", size=10, sha256="a" * 64,
                                           url=config["baseUrl"] + "/releases/24/app.dmg", minimumOsVersion="13.0")])
            payload = dict(schema=1, appId="saien.quotadog", channel="stable", sequence=now + 100,
                           issuedAt=now - 20 * 86400, expiresAt=now + 10 * 86400, releases=[release])
            old_feed = root / "old.json"
            sign_feed(payload, old_feed, config, private)
            remote = {}
            writes = []

            def fetch(url, destination, maximum=262144):
                self.assertEqual(url, config["baseUrl"] + "/feeds/stable.json")
                if "feed" not in remote: return False
                Path(destination).write_bytes(remote["feed"])
                return True

            def upload(key, path, content_type):
                self.assertEqual(key, "feeds/stable.json")
                self.assertEqual(content_type, "application/json")
                writes.append(Path(path).read_bytes())
                remote["feed"] = writes[-1]

            arguments = ["publish_update.py", "--renew-if-due", "--publish", "--private-key", str(private)]
            with patch.object(publish_update, "ROOT", root), patch.object(publish_update, "fetch", fetch), \
                    patch.object(publish_update, "upload", upload), patch.object(sys, "argv", arguments), \
                    patch.object(publish_update.time, "time", return_value=now):
                # A scheduled job must not initialize an unpublished stable channel.
                publish_update.main()
                self.assertEqual(writes, [])
                remote["feed"] = old_feed.read_bytes()
                publish_update.main()
                self.assertEqual(len(writes), 1)
                renewed = verify_feed(remote["feed"], config)
                self.assertEqual(renewed["releases"], [release])
                self.assertEqual(renewed["sequence"], payload["sequence"] + 1)
                self.assertEqual(renewed["issuedAt"], now)
                self.assertEqual(renewed["expiresAt"], now + 30 * 86400)
                # Running daily is idempotent until the renewal window is reached.
                publish_update.main()
                self.assertEqual(len(writes), 1)
                envelope = json.loads(remote["feed"])
                envelope["payload"] = base64.b64encode(json.dumps(payload).encode()).decode()
                remote["feed"] = json.dumps(envelope).encode()
                with self.assertRaises(subprocess.CalledProcessError):
                    publish_update.main()
                self.assertEqual(len(writes), 1)

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
