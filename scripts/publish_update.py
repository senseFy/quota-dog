#!/usr/bin/env python3
"""Prepare a signed macOS feed; upload packages then the feed only with --publish."""
import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import time
import uuid
from updater_tools import ROOT, tool


def properties(path):
    return dict(line.split("=", 1) for line in Path(path).read_text().splitlines() if line and not line.startswith("#"))


def digest(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""): result.update(chunk)
    return result.hexdigest()


def fetch(url, destination, maximum=262144):
    status = subprocess.check_output(["curl", "--silent", "--show-error", "--retry", "2", "--connect-timeout", "15", "--max-time", "180",
        "--proto", "=https", "--max-filesize", str(maximum), "--output", str(destination), "--write-out", "%{http_code}", url], text=True)
    if status not in ("200", "404"): raise RuntimeError(f"Expected direct HTTP 200/404, received {status}: {url}")
    return status == "200"


def verify_feed(data, config):
    envelope = json.loads(data)
    payload = base64.b64decode(envelope["payload"], validate=True)
    signature = next(s for s in envelope["signatures"] if s["keyId"] == config["keyId"])
    with tempfile.TemporaryDirectory(prefix="quotadog-verify-") as directory:
        root = Path(directory)
        (root / "public.der").write_bytes(base64.b64decode(config["publicKey"], validate=True))
        (root / "message").write_bytes(b"kmp-updater:manifest:v1\n" + payload)
        (root / "signature").write_bytes(base64.b64decode(signature["signature"], validate=True))
        subprocess.run(["openssl", "pkeyutl", "-verify", "-pubin", "-keyform", "DER", "-inkey", str(root / "public.der"),
            "-rawin", "-in", str(root / "message"), "-sigfile", str(root / "signature")], check=True, capture_output=True)
    return json.loads(payload)


def sign_feed(payload, output, config, private_key):
    source = output.with_suffix(".payload.json")
    source.write_text(json.dumps(payload, ensure_ascii=False) + "\n")
    subprocess.run([str(tool("publisher")), "sign", str(source), str(output), config["keyId"], str(private_key)], check=True)
    # This also proves the private key matches the public key pinned in the app.
    verified = verify_feed(output.read_bytes(), config)
    if verified != payload: raise RuntimeError("Signed payload differs from the requested release")


def inspect_dmg(path, config):
    subprocess.run(["xcrun", "stapler", "validate", str(path)], check=True)
    subprocess.run(["spctl", "--assess", "--type", "open", "--context", "context:primary-signature", str(path)], check=True)
    mounted = plistlib.loads(subprocess.check_output(["hdiutil", "attach", "-plist", "-readonly", "-nobrowse", str(path)]))
    mount = Path(next(x["mount-point"] for x in mounted["system-entities"] if "mount-point" in x))
    try:
        apps = list(mount.glob("*.app"))
        if len(apps) != 1 or apps[0].is_symlink(): raise RuntimeError("DMG must contain one top-level app")
        app = apps[0]
        subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
        subprocess.run(["xcrun", "stapler", "validate", str(app)], check=True)
        subprocess.run(["spctl", "--assess", "--type", "execute", str(app)], check=True)
        identity = subprocess.check_output(["codesign", "-dv", "--verbose=4", str(app)], stderr=subprocess.STDOUT, text=True)
        if f"TeamIdentifier={config['teamId']}" not in identity or "Authority=Developer ID Application:" not in identity:
            raise RuntimeError("Wrong Developer ID publisher")
        info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
        if info["CFBundleIdentifier"] != "saien.quotadog": raise RuntimeError("Wrong app bundle ID")
        sequence = info.get("KMPUpdaterRelease")
        if not isinstance(sequence, str) or not sequence.isdecimal() or int(sequence) <= 0:
            raise RuntimeError("Missing KMPUpdaterRelease string")
        if not (app / "Contents/Helpers/kmp-updater-helper").is_file(): raise RuntimeError("Missing updater helper")
        arch = subprocess.check_output(["lipo", "-archs", str(app / "Contents/runtime/Contents/Home/lib/server/libjvm.dylib")], text=True).strip()
        target = {"arm64": "macos-aarch64", "x86_64": "macos-x64"}[arch]
        return int(sequence), info["CFBundleShortVersionString"], target
    finally:
        subprocess.run(["hdiutil", "detach", str(mount)], check=True)


def upload(key, path, content_type):
    subprocess.run(["wrangler", "r2", "object", "put", "quotadog-releases/" + key,
        "--remote", "--file", str(path), "--content-type", content_type,
        "--config", str(ROOT / "infra/cloudflare/wrangler.jsonc")], check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dmg", type=Path)
    mode.add_argument("--refresh", action="store_true", help="Renew an existing feed without an app release")
    mode.add_argument("--initialize", action="store_true", help="Create an empty feed if the channel has never been published")
    parser.add_argument("--notes", type=Path, help="UTF-8 release notes")
    parser.add_argument("--expires-days", type=int, default=30)
    parser.add_argument("--private-key", type=Path, default=Path.home() / ".config/quotadog/updater/quotadog-2026.private-key")
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.expires_days <= 90: parser.error("expires-days must be 1..90")
    config = properties(ROOT / "updater.properties")
    output = ROOT / "build/update-publication" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])
    output.mkdir(parents=True)
    # Use one publisher machine/CI concurrency group. This lock serializes local runs.
    lock = args.private_key.parent / "publish.lock"
    with lock.open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        old_file = output / "previous.json"
        url = config["baseUrl"] + "/feeds/stable.json"
        exists = fetch(url, old_file)
        old_bytes = old_file.read_bytes() if exists else None
        old = verify_feed(old_bytes, config) if exists else None
        if old and (old["appId"] != "saien.quotadog" or old["channel"] != "stable" or old["schema"] != 1):
            raise RuntimeError("Published feed scope does not match QuotaDog")
        if args.initialize and exists: raise RuntimeError("Feed already exists; use --refresh")
        if args.refresh and not exists: raise RuntimeError("No existing feed to refresh")
        releases = old["releases"] if old else []
        artifact = None
        if args.dmg:
            args.dmg = args.dmg.resolve(strict=True)
            sequence, version, target = inspect_dmg(args.dmg, config)
            current = properties(ROOT / "version.properties")
            if sequence != int(current["VERSION_CODE"]) or version != current["VERSION_NAME"]:
                raise RuntimeError("DMG does not match version.properties")
            if releases and sequence < max(r["sequence"] for r in releases):
                raise RuntimeError("Refusing to publish an older release")
            key = f"releases/{sequence}/QuotaDog-{version}-{target}.dmg"
            artifact = dict(target=target, kind="dmg", url=config["baseUrl"] + "/" + key,
                size=args.dmg.stat().st_size, sha256=digest(args.dmg), minimumOsVersion="13.0")
            release = next((r for r in releases if r["sequence"] == sequence), None)
            if release:
                if release["version"] != version or any(a["target"] == target for a in release["artifacts"]):
                    raise RuntimeError("This release/architecture already exists; bump the build or use --refresh")
                release["artifacts"].append(artifact)
            else:
                releases.append(dict(sequence=sequence, version=version, artifacts=[artifact], minimumInstalledSequence=0,
                    notes={"en": args.notes.read_text()} if args.notes else {}))
        now = int(time.time())
        payload = dict(schema=1, appId="saien.quotadog", channel="stable", sequence=max(now, (old or {}).get("sequence", 0) + 1),
            issuedAt=now, expiresAt=now + args.expires_days * 86400, releases=releases)
        signed = output / "stable.json"
        sign_feed(payload, signed, config, args.private_key)
        print(f"Prepared signed feed: {signed}", flush=True)
        if not args.publish:
            print("No files uploaded. Add --publish to upload the package and then the signed feed.")
            return
        if artifact:
            downloaded = output / "verified-package.dmg"
            if not fetch(artifact["url"], downloaded, artifact["size"]):
                upload(key, args.dmg, "application/x-apple-diskimage")
                if not fetch(artifact["url"], downloaded, artifact["size"]): raise RuntimeError("Uploaded package is not reachable")
            if downloaded.stat().st_size != artifact["size"] or digest(downloaded) != artifact["sha256"]:
                raise RuntimeError("Public package bytes differ; refusing to publish the feed")
            downloaded.unlink()
        latest = output / "latest.json"
        present = fetch(url, latest)
        if (latest.read_bytes() if present else None) != old_bytes:
            raise RuntimeError("Feed changed during preparation; retry from the new feed")
        upload("feeds/stable.json", signed, "application/json")
        if not fetch(url, latest) or latest.read_bytes() != signed.read_bytes():
            raise RuntimeError("Published feed verification failed")
        print(f"Published and verified {url}")


if __name__ == "__main__":
    main()
