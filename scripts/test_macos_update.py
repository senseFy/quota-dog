#!/usr/bin/env python3
"""Exercise actual QuotaDog updater code in isolated Developer ID/notarized app copies."""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import plistlib
import platform
import shutil
import signal
import subprocess
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from notarize_macos import notarize
from publish_update import digest, properties, sign_feed, upload
from sign_macos_app import sign_app
from updater_tools import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity", default="Developer ID Application: yangsen feng (45V6QJP3A2)")
    parser.add_argument("--notary-profile", default="saytive-notary")
    parser.add_argument("--private-key", type=Path, default=Path.home() / ".config/quotadog/updater/quotadog-2026.private-key")
    parser.add_argument("--resume", type=Path)
    parser.add_argument("--case", choices=("all", "success", "receipt-failure", "bad-signature", "bad-artifact"), default="all")
    args = parser.parse_args()
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        parser.error("This acceptance harness requires an Apple Silicon Mac")
    config = properties(ROOT / "updater.properties")
    root = args.resume.resolve() if args.resume else ROOT / "build/updater-acceptance" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
    root.mkdir(parents=True, exist_ok=True)
    (root / ".quotadog-updater-acceptance").write_text("v1\n")
    channel = "acceptance-" + root.name
    feed_key = f"feeds/{channel}.json"
    artifact_key = f"releases/2/QuotaDog-{channel}-macos-aarch64.dmg"
    print(f"Acceptance directory: {root}", flush=True)
    report = {"cases": [], "passed": False, "selection": args.case, "channel": channel, "scope": "isolated QuotaDog updater host; Developer ID + notarization + Cloudflare HTTPS"}
    report_path = root / ("report.json" if args.case == "all" else f"report-{args.case}.json")
    uploaded = []
    try:
        pending_notarization = []
        for sequence in (1, 2):
            app = root / f"release-{sequence}" / "QuotaDog Acceptance.app"
            if not (app.parent / "ready").exists():
                subprocess.run([str(ROOT / "gradlew"), ":composeApp:createDistributable", "--console=plain",
                    f"-Pquotadog.acceptanceDirectory={root}", f"-Pquotadog.acceptanceSequence={sequence}",
                    f"-Pquotadog.acceptanceChannel={channel}"], cwd=ROOT, check=True)
                app.parent.mkdir(exist_ok=True)
                if app.exists(): shutil.rmtree(app)
                subprocess.run(["ditto", str(ROOT / "composeApp/build/compose/binaries/main/app/QuotaDog Acceptance.app"), str(app)], check=True)
                sign_app(app, args.identity)
                pending_notarization.append(app)
        def finish_app(app):
            notarize(app, args.notary_profile)
            subprocess.run(["spctl", "--assess", "--type", "execute", str(app)], check=True)
            (app.parent / "ready").touch()
        # Independent signed app copies can be notarized concurrently; Gradle builds stay serial.
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(finish_app, pending_notarization))
        dmg = root / "update.dmg"
        if not (root / "dmg-ready").exists():
            stage = root / "dmg-stage"
            stage.mkdir(exist_ok=True)
            if not (stage / "QuotaDog Acceptance.app").exists():
                subprocess.run(["ditto", str(root / "release-2/QuotaDog Acceptance.app"), str(stage / "QuotaDog Acceptance.app")], check=True)
            subprocess.run(["hdiutil", "create", "-ov", "-volname", "QuotaDog Acceptance", "-srcfolder", str(stage), "-format", "UDZO", str(dmg)], check=True)
            subprocess.run(["codesign", "--force", "--timestamp", "--sign", args.identity, str(dmg)], check=True)
            notarize(dmg, args.notary_profile)
            (root / "dmg-ready").touch()
        if args.case != "bad-signature":
            uploaded.append(artifact_key)
            upload(artifact_key, dmg, "application/x-apple-diskimage")
        artifact = dict(target="macos-aarch64", kind="dmg", url=config["baseUrl"] + "/" + artifact_key,
            size=dmg.stat().st_size, sha256=digest(dmg), minimumOsVersion="13.0")
        for index, (scenario, expected) in enumerate((('success', 'success'), ('receipt-failure', 'success'), ('bad-signature', 'rejected-signature'), ('bad-artifact', 'rejected-artifact'))):
            if args.case != "all" and args.case != scenario: continue
            attempt = root / (scenario + "-" + uuid.uuid4().hex[:6])
            attempt.mkdir()
            (attempt / "data").mkdir(mode=0o700)
            (attempt / "data/user-data.txt").write_text("preserve-this-data\n")
            (attempt / "scenario.txt").write_text(scenario)
            app = attempt / "QuotaDog Acceptance.app"
            subprocess.run(["ditto", str(root / "release-1/QuotaDog Acceptance.app"), str(app)], check=True)
            now = int(time.time())
            candidate = copy.deepcopy(artifact)
            if scenario == "bad-artifact": candidate["sha256"] = "0" * 64
            payload = dict(schema=1, appId="saien.quotadog.acceptance", channel=channel, sequence=now + index,
                issuedAt=now, expiresAt=now + 86400, releases=[dict(sequence=2, version="Acceptance 2", artifacts=[candidate],
                minimumInstalledSequence=0, notes={"en": "Isolated QuotaDog update acceptance."})])
            feed = attempt / "feed.json"
            sign_feed(payload, feed, config, args.private_key)
            if scenario == "bad-signature":
                envelope = json.loads(feed.read_text())
                signature = bytearray(base64.b64decode(envelope["signatures"][0]["signature"]))
                signature[0] ^= 1
                envelope["signatures"][0]["signature"] = base64.b64encode(signature).decode()
                feed.write_text(json.dumps(envelope))
            if feed_key not in uploaded: uploaded.append(feed_key)
            upload(feed_key, feed, "application/json")
            print(f"Running {scenario}…", flush=True)
            subprocess.run(["open", "-n", str(app)], check=True)
            deadline = time.monotonic() + 1000
            while not (attempt / "result.txt").exists():
                if time.monotonic() > deadline: raise TimeoutError(f"Acceptance timed out: {attempt}")
                time.sleep(1)
            outcome = (attempt / "result.txt").read_text().strip()
            if outcome != expected: raise RuntimeError(f"{scenario}: {outcome}; see {attempt}")
            info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
            if info["KMPUpdaterRelease"] != ("2" if expected == "success" else "1"):
                raise RuntimeError("Unexpected installed release")
            events = (attempt / "events.log").read_text().splitlines()
            if expected == "success":
                pids = {line.split()[1] for line in events if line.endswith(" boot")}
                if len(pids) != 2: raise RuntimeError("Upgrade did not start a new process")
                if not any(line.endswith("confirmed-data-preserved") for line in events): raise RuntimeError("No startup confirmation")
                if scenario == "receipt-failure" and not any(line.endswith("quit-blocked") for line in events):
                    raise RuntimeError("Quit was not blocked after receipt failure")
            if (attempt / "data/user-data.txt").read_text() != "preserve-this-data\n": raise RuntimeError("Application data changed")
            subprocess.run(["codesign", "--verify", "--deep", "--strict", str(app)], check=True)
            report["cases"].append(dict(scenario=scenario, outcome=outcome, directory=str(attempt), events=events))
            report_path.write_text(json.dumps(report, indent=2) + "\n")
            print(f"PASS {scenario}", flush=True)
        report["passed"] = True
    finally:
        report["cleanup_errors"] = []
        for key in reversed(uploaded):
            result = subprocess.run(["wrangler", "r2", "object", "delete", "quotadog-releases/" + key, "--remote",
                "--config", str(ROOT / "infra/cloudflare/wrangler.jsonc")])
            if result.returncode: report["cleanup_errors"].append(key)
        # Only terminate processes positively identified as this fixture's executable.
        for events in root.glob("*/events.log"):
            for line in events.read_text().splitlines():
                pid = int(line.split()[1])
                process = subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True)
                if str(root) in process.stdout and "QuotaDog Acceptance.app/Contents/MacOS/" in process.stdout:
                    try: os.kill(pid, signal.SIGTERM)
                    except ProcessLookupError: pass
        report_path.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Acceptance report: {report_path}", flush=True)


if __name__ == "__main__":
    main()
