#!/usr/bin/env python3
"""Notarize/staple an app (via ZIP) or a DMG, retaining submission receipts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def notarize(path, profile=None):
    path = Path(path).resolve(strict=True)
    credentials = ["--keychain-profile", profile or os.environ.get("NOTARY_PROFILE", "saytive-notary")]
    if any(os.environ.get(x) for x in ("NOTARY_KEY_PATH", "NOTARY_KEY_ID", "NOTARY_ISSUER")):
        credentials = ["--key", os.environ["NOTARY_KEY_PATH"], "--key-id", os.environ["NOTARY_KEY_ID"], "--issuer", os.environ["NOTARY_ISSUER"]]
    artifact = path
    if path.suffix == ".app":
        artifact = path.with_suffix(".notarization.zip")
        subprocess.run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent", str(path), str(artifact)], check=True)
    receipt = path.with_suffix(path.suffix + ".notary.json")
    with artifact.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    previous = json.loads(receipt.read_text()) if receipt.exists() else {}
    if previous.get("sha256") == digest:
        submission = previous["id"]
    else:
        print(f"Submitting {artifact.name} for notarization…", flush=True)
        output = subprocess.check_output(["xcrun", "notarytool", "submit", str(artifact), *credentials, "--output-format", "json"], text=True)
        submission = json.loads(output)["id"]
        receipt.write_text(json.dumps({"id": submission, "sha256": digest}) + "\n")
    deadline = time.monotonic() + 1800
    while True:
        result = json.loads(subprocess.check_output(["xcrun", "notarytool", "info", submission, *credentials, "--output-format", "json"], text=True))
        if result["status"] == "Accepted": break
        if result["status"] != "In Progress":
            subprocess.run(["xcrun", "notarytool", "log", submission, *credentials, str(receipt.with_suffix(".log.json"))], check=True)
            raise RuntimeError(f"Notarization {result['status']}; see {receipt}")
        if time.monotonic() > deadline: raise TimeoutError(f"Notarization pending: {submission}; rerun to resume")
        time.sleep(10)
    receipt.write_text(json.dumps({"id": submission, "sha256": digest, "status": "Accepted"}) + "\n")
    subprocess.run(["xcrun", "stapler", "staple", str(path)], check=True)
    subprocess.run(["xcrun", "stapler", "validate", str(path)], check=True)
    print(f"Notarized and stapled {path.name}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("--profile")
    args = parser.parse_args()
    notarize(args.path, args.profile)
