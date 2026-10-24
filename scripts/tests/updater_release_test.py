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
import record_update_feed


class PublisherTests(unittest.TestCase):
    def test_release_retry_preserves_unicode_notes_and_never_overwrites_conflicts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = root / "release.private-key"
            public = subprocess.check_output([str(tool("publisher")), "keygen", str(private)], text=True).strip()
            config = dict(keyId="test-key", publicKey=public, baseUrl="https://updates.example.test/updates")
            (root / "updater.properties").write_text("\n".join(f"{key}={value}" for key, value in config.items()))
            (root / "version.properties").write_text("VERSION_NAME=1.0.13\nVERSION_CODE=25\n")
            dmg = root / "release.dmg"
            dmg.write_bytes(b"notarized-fixture")
            notes = root / "notes.md"
            notes.write_text("- 改进退出行为。\n", encoding="utf-8")
            now = int(time.time())
            payload = dict(schema=1, appId="saien.quotadog", channel="stable", sequence=1,
                           issuedAt=now, expiresAt=now + 86400, releases=[])
            feed = root / "old.json"
            sign_feed(payload, feed, config, private)
            remote = {"feeds/stable.json": feed.read_bytes()}
            writes = []

            def fetch(url, destination, maximum=262144):
                key = url.removeprefix(config["baseUrl"] + "/")
                if key not in remote: return False
                Path(destination).write_bytes(remote[key])
                return True

            def upload(key, path, content_type):
                remote[key] = Path(path).read_bytes()
                writes.append(key)

            arguments = ["publish_update.py", "--dmg", str(dmg), "--notes", str(notes), "--notes-language", "zh-CN",
                         "--allow-existing", "--publish", "--private-key", str(private)]
            with patch.object(publish_update, "ROOT", root), patch.object(publish_update, "fetch", fetch), \
                    patch.object(publish_update, "upload", upload), patch.object(sys, "argv", arguments), \
                    patch.object(publish_update, "inspect_dmg", return_value=(25, "1.0.13", "macos-aarch64")):
                publish_update.main()
                self.assertEqual(writes, ["releases/25/QuotaDog-1.0.13-macos-aarch64.dmg", "feeds/stable.json"])
                release = verify_feed(remote["feeds/stable.json"], config)["releases"][0]
                self.assertEqual(release["notes"], {"zh-CN": notes.read_text(encoding="utf-8")})
                signed = remote["feeds/stable.json"]
                publish_update.main()
                self.assertEqual(len(writes), 2)
                self.assertEqual(remote["feeds/stable.json"], signed)
                # Normal publishing still rejects duplicates unless explicitly resuming.
                with patch.object(sys, "argv", [arg for arg in arguments if arg != "--allow-existing"]):
                    with self.assertRaisesRegex(RuntimeError, "already exists"):
                        publish_update.main()
                notes.write_text("Different notes\n", encoding="utf-8")
                with self.assertRaisesRegex(RuntimeError, "already exists"):
                    publish_update.main()
                notes.write_text("- 改进退出行为。\n", encoding="utf-8")
                expiry = verify_feed(signed, config)["expiresAt"]
                with patch.object(publish_update.time, "time", return_value=expiry + 1):
                    with self.assertRaisesRegex(RuntimeError, "feed has expired"):
                        publish_update.main()
                remote[writes[0]] = b"tampered-package"
                with self.assertRaisesRegex(RuntimeError, "public package does not match"):
                    publish_update.main()
                self.assertEqual(len(writes), 2)

    def test_public_audit_records_only_a_new_verified_feed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = root / "release.private-key"
            public = subprocess.check_output([str(tool("publisher")), "keygen", str(private)], text=True).strip()
            config = dict(keyId="test-key", publicKey=public, baseUrl="https://updates.example.test/updates")
            (root / "updater.properties").write_text("\n".join(f"{k}={v}" for k, v in config.items()))
            now = int(time.time())
            payload = dict(schema=1, appId="saien.quotadog", channel="stable", sequence=10,
                           issuedAt=now, expiresAt=now + 86400, releases=[])
            old = root / "old.json"
            new = root / "new.json"
            sign_feed(payload, old, config, private)
            sign_feed(dict(payload, sequence=11), new, config, private)
            remote = dict(feed=old.read_bytes())
            writes = []
            original = subprocess.check_output

            def fetch(url, destination, maximum=262144):
                Path(destination).write_bytes(remote["feed"])
                return True

            def github(command, **kwargs):
                if command[0] != "gh": return original(command, **kwargs)
                if "PUT" in command:
                    writes.append(json.loads(kwargs["input"]))
                    return b'{"commit":{"html_url":"https://example.test/commit"}}'
                return json.dumps(dict(sha="existing-file-sha", content=base64.b64encode(old.read_bytes()).decode())).encode()

            with patch.object(record_update_feed, "ROOT", root), patch.object(record_update_feed, "fetch", fetch), \
                    patch.object(record_update_feed.subprocess, "check_output", github):
                record_update_feed.main()
                self.assertEqual(writes, [])
                remote["feed"] = new.read_bytes()
                record_update_feed.main()
                self.assertEqual(len(writes), 1)
                self.assertEqual(writes[0]["sha"], "existing-file-sha")
                self.assertEqual(writes[0]["branch"], "main")
                self.assertEqual(base64.b64decode(writes[0]["content"]), new.read_bytes())
                envelope = json.loads(new.read_bytes())
                envelope["payload"] = base64.b64encode(json.dumps(dict(payload, sequence=12)).encode()).decode()
                remote["feed"] = json.dumps(envelope).encode()
                with self.assertRaises(subprocess.CalledProcessError): record_update_feed.main()
                self.assertEqual(len(writes), 1)

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
