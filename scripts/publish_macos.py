#!/usr/bin/env python3
"""Interactively release a signed Apple Silicon DMG, with resumable publication."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

from publish_update import ROOT, digest, fetch, inspect_dmg, notes_language, properties, verify_feed
from record_update_feed import RECORD, REPOSITORY

SITE = "infra/cloudflare/public/index.html"
RUNS = ROOT / "build/release/macos"
DEFAULT_KEY = Path.home() / ".config/quotadog/updater/quotadog-2026.private-key"


def run(*command, env=None, capture=False):
    command = [str(part) for part in command]
    print("+ " + shlex.join(command), flush=True)
    result = subprocess.run(command, cwd=ROOT, env=env, check=True, text=True,
                            stdout=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else None


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def ancestor(older, newer):
    return subprocess.run(["git", "merge-base", "--is-ancestor", older, newer], cwd=ROOT).returncode == 0


def version_tuple(value):
    if not re.fullmatch(r"[1-9]\d*\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", value, flags=re.ASCII):
        raise argparse.ArgumentTypeError("Version must be x.y.z, with major >= 1 and no leading zeros")
    return tuple(map(int, value.split(".")))


def version_name(value):
    version_tuple(value)
    return value


def ask(label, default=None, validate=None):
    if not sys.stdin.isatty():
        raise RuntimeError(f"Missing {label}; pass explicit options when stdin is not a terminal (see --help)")
    while True:
        answer = input(f"{label}" + (f" [{default}]" if default is not None else "") + ": ").strip() or default or ""
        try:
            if validate:
                validate(answer)
            return answer
        except (argparse.ArgumentTypeError, ValueError) as error:
            print(error, flush=True)


def yes_no(value):
    if value.lower() not in ("yes", "no", "y", "n"):
        raise ValueError("Enter yes or no")
    return value.lower() in ("yes", "y")


def confirm(label, yes=False):
    return yes or yes_no(ask(label + " (yes/no)", "no", yes_no))


def normalize_notes(notes):
    notes = notes.strip() + "\n"
    if not notes.strip() or re.fullmatch(r"[-*\s]*(?:TBD|TODO)[.\s]*", notes, flags=re.IGNORECASE):
        raise ValueError("Write release notes before publishing; empty notes and placeholders are not accepted")
    return notes


def collect_notes(path=None):
    if path:
        # An explicit CLI file stays unambiguous and fails fast in automation.
        return normalize_notes(path.expanduser().read_text(encoding="utf-8"))

    def validate_mode(value):
        if value not in ("1", "2"):
            raise ValueError("Enter 1 to type release notes, or 2 to read a file")

    while True:
        print("\nRelease notes input:\n  1. Type or paste the release notes here\n  2. Read a UTF-8 Markdown file", flush=True)
        mode = ask("Choose 1 or 2", "1", validate_mode)
        if mode == "2":
            while True:
                filename = ask("Notes file path (Enter to return to input choices)")
                if not filename:
                    break
                try:
                    return normalize_notes(Path(filename).expanduser().read_text(encoding="utf-8"))
                except (OSError, ValueError) as error:
                    print(f"Could not read release notes: {error}\nEnter a file path, or press Enter to choose direct input.", flush=True)
        else:
            print("Type or paste the release notes text, for example: Performance optimization\n"
                  "Finish with a single '.' on its own line, then press Enter:", flush=True)
            lines = []
            while True:
                line = input()
                if line == ".":
                    break
                lines.append(line)
            try:
                return normalize_notes("\n".join(lines))
            except ValueError as error:
                print(error, flush=True)


def collect(args):
    current = properties(ROOT / "version.properties")
    major, minor, patch = version_tuple(current["VERSION_NAME"])
    def validate_version(value):
        if version_tuple(value) <= (major, minor, patch):
            raise ValueError("Choose a version newer than version.properties; do not bump separately before this command")

    if sys.stdin.isatty():
        print("Press Enter to accept the default shown in [brackets].", flush=True)
    version = args.version or ask("New version", f"{major}.{minor}.{patch + 1}", validate_version)
    validate_version(version)
    build = int(current["VERSION_CODE"]) + 1
    if not 1 <= build <= 2100000000:
        raise RuntimeError("Shared build number is outside the supported store range")
    language = args.notes_language or ask("Release notes language (en = English, zh-CN = Chinese)", "en", notes_language)
    notes = collect_notes(args.notes)
    github = args.github_release or ask("Also publish a GitHub Release (yes/no)", "yes", yes_no)
    return dict(version=version, build=build, notes=notes, language=language, github=yes_no(github),
                tag=f"macos-v{version}-build{build}", private_key=str(args.private_key.expanduser().resolve()))


def update_site(text, version, build):
    text, count = re.subn(r'(<p id="macos-version">).*?(</p>)',
                         rf'\g<1>Version {version} for Apple Silicon. Requires macOS 13 or later.\2', text)
    if count != 1:
        raise RuntimeError("Expected exactly one macos-version paragraph in the website")
    path = f"/updates/releases/{build}/QuotaDog-{version}-macos-aarch64.dmg"
    text, count = re.subn(r'(<a id="macos-download" href=")[^"]+("[^>]*>)', rf'\g<1>{path}\2', text)
    if count != 1:
        raise RuntimeError("Expected exactly one macos-download link in the website")
    return text


def plan_files(state):
    paths = ("version.properties", "CHANGELOG.md", SITE)
    before = {path: (ROOT / path).read_text(encoding="utf-8") if (ROOT / path).exists() else None for path in paths}
    version = re.sub(r"(?m)^VERSION_NAME=.*$", "VERSION_NAME=" + state["version"], before["version.properties"])
    version = re.sub(r"(?m)^VERSION_CODE=.*$", "VERSION_CODE=" + str(state["build"]), version)
    changelog = before["CHANGELOG.md"] or "# Changelog\n\n"
    heading = f"## {state['version']} ({state['build']}) — {time.strftime('%Y-%m-%d')}\n\n"
    entry = heading + state["notes"] + "\n"
    if changelog.startswith("# Changelog\n"):
        changelog = "# Changelog\n\n" + entry + changelog[len("# Changelog\n"):].lstrip("\n")
    else:
        changelog = entry + changelog
    after = {"version.properties": version, "CHANGELOG.md": changelog,
             SITE: update_site(before[SITE], state["version"], state["build"])}
    return {path: dict(before=before[path], after=after[path]) for path in paths}


def save(directory, state):
    temporary = directory / "state.tmp"
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(state, output, ensure_ascii=False, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    temporary.chmod(0o600)
    temporary.replace(directory / "state.json")


def assert_clean():
    if git("branch", "--show-current") != "main" or git("status", "--porcelain"):
        raise RuntimeError("Run from a clean main checkout: commit or stash your changes first")


def check_source(state):
    assert_clean()
    head = git("rev-parse", "HEAD")
    source = state["source"]
    if head != source:
        if "Package DMG" not in state["done"]:
            raise RuntimeError("Checkout changed before packaging; return to the saved source commit")
        changed = git("diff", "--name-only", source, head).splitlines()
        if not ancestor(source, head) or any(path != RECORD for path in changed):
            raise RuntimeError("Source changed since this release was built; resume from its source commit")


def prepare_commit(directory, state):
    if "source" in state:
        check_source(state)
        return
    if git("branch", "--show-current") != "main":
        raise RuntimeError("Release preparation requires main")
    head = git("rev-parse", "HEAD")
    planned = state["files"]
    if head != state["base"]:
        # A commit can succeed immediately before a process interruption.
        if git("rev-parse", "HEAD^") != state["base"] or git("status", "--porcelain"):
            raise RuntimeError("Checkout changed during release preparation")
        if set(git("diff", "--name-only", state["base"], head).splitlines()) != set(planned):
            raise RuntimeError("Unexpected changes in the release preparation commit")
        if any((ROOT / path).read_text(encoding="utf-8") != values["after"] for path, values in planned.items()):
            raise RuntimeError("Release preparation commit does not match the saved plan")
    else:
        allowed = set(planned)
        changed = set(git("diff", "--name-only", "HEAD").splitlines())
        changed.update(git("ls-files", "--others", "--exclude-standard").splitlines())
        if changed - allowed:
            raise RuntimeError("Unrelated changes appeared during release preparation")
        for path, values in planned.items():
            destination = ROOT / path
            existing = destination.read_text(encoding="utf-8") if destination.exists() else None
            if existing not in (values["before"], values["after"]):
                raise RuntimeError(f"Release preparation would overwrite an edit to {path}")
        for path, values in planned.items():
            (ROOT / path).write_text(values["after"], encoding="utf-8")
        run("git", "add", "--", *planned)
        run("git", "commit", "-m", f"Prepare macOS {state['version']} release ({state['build']})")
    state["source"] = git("rev-parse", "HEAD")
    save(directory, state)
    check_source(state)


def preflight(state):
    if platform.system() != "Darwin" or platform.machine() != "arm64":
        raise RuntimeError("This command currently publishes Apple Silicon releases; run on an ARM64 Mac")
    if sys.version_info < (3, 11):
        raise RuntimeError("Python 3.11 or newer is required")
    for name in ("git", "gh", "wrangler", "openssl", "curl", "xcrun", "codesign", "hdiutil", "security"):
        if not shutil.which(name):
            raise RuntimeError(f"Missing command: {name}")
    origin = git("remote", "get-url", "origin")
    if origin.removesuffix(".git") not in (f"git@github.com:{REPOSITORY}", f"https://github.com/{REPOSITORY}"):
        raise RuntimeError(f"origin must point to {REPOSITORY}")
    env = os.environ.copy()
    # Explicit overrides must be valid; otherwise discover a full cached/local JDK 21.
    candidates = [Path(env["JAVA_HOME"])] if env.get("JAVA_HOME") else list((Path.home() / ".gradle/jdks").glob("*/Contents/Home"))
    if not env.get("JAVA_HOME"):
        candidates += list((Path.home() / ".gradle/jdks").glob("*/*/Contents/Home"))
        candidates += list(Path("/Library/Java/JavaVirtualMachines").glob("*/Contents/Home"))
        candidates += [Path("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home")]
    java = next((path for path in candidates if (path / "release").is_file()
                 and re.search(r'^JAVA_VERSION="21[.\"]', (path / "release").read_text(), re.MULTILINE)
                 and (path / "bin/jpackage").is_file()), None)
    if not java:
        raise RuntimeError("Set JAVA_HOME to a full JDK 21 (including jpackage)")
    env["JAVA_HOME"] = str(java)
    env["PATH"] = str(java / "bin") + os.pathsep + env["PATH"]
    if not run("openssl", "version", capture=True).startswith("OpenSSL 3."):
        raise RuntimeError("OpenSSL 3 must be on PATH")
    key = Path(state["private_key"])
    if not key.is_file() or key.stat().st_mode & 0o077:
        raise RuntimeError("Manifest private key must exist and be readable only by its owner (chmod 600)")
    config = properties(ROOT / "updater.properties")
    identities = run("security", "find-identity", "-p", "codesigning", "-v", capture=True)
    matches = re.findall(r'"(Developer ID Application: [^"\n]+ \(' + re.escape(config["teamId"]) + r'\))"', identities)
    identity = env.get("CODESIGN_IDENTITY") or next(iter(matches), None)
    if identity not in matches:
        raise RuntimeError("No matching Developer ID Application identity for updater.properties teamId")
    env["CODESIGN_IDENTITY"] = identity
    # Do not let unrelated local packaging overrides change this release's identity.
    for name in ("SKIP_CODESIGN", "RELEASE_VERSION", "DMG_NAME", "DMG_VOLNAME"):
        env.pop(name, None)
    run("gh", "auth", "status", "--hostname", "github.com")
    run("wrangler", "whoami", capture=True)
    notary_fields = ("NOTARY_KEY_PATH", "NOTARY_KEY_ID", "NOTARY_ISSUER")
    if any(env.get(name) for name in notary_fields):
        if not all(env.get(name) for name in notary_fields):
            raise RuntimeError("Set all of NOTARY_KEY_PATH, NOTARY_KEY_ID and NOTARY_ISSUER")
        credentials = ["--key", env["NOTARY_KEY_PATH"], "--key-id", env["NOTARY_KEY_ID"], "--issuer", env["NOTARY_ISSUER"]]
    else:
        credentials = ["--keychain-profile", env.get("NOTARY_PROFILE", "saytive-notary")]
    run("xcrun", "notarytool", "history", *credentials, "--output-format", "json", env=env, capture=True)
    run("git", "fetch", "origin", "main")
    remote = git("rev-parse", "origin/main")
    if not ancestor(remote, git("rev-parse", "HEAD")):
        # Feed audits may arrive while a release is being prepared.
        source = state.get("source")
        if not source or not ancestor(source, remote) or any(p != RECORD for p in git("diff", "--name-only", source, remote).splitlines()):
            raise RuntimeError("origin/main has other changes; synchronize main before starting a release")
    return env


def check_feed(state):
    config = properties(ROOT / "updater.properties")
    with tempfile.TemporaryDirectory(prefix="quotadog-release-feed-") as directory:
        path = Path(directory) / "stable.json"
        if not fetch(config["baseUrl"] + "/feeds/stable.json", path):
            raise RuntimeError("Stable feed is missing; initialize it explicitly before using this command")
        payload = verify_feed(path.read_bytes(), config)
        if (payload["schema"], payload["appId"], payload["channel"]) != (1, "saien.quotadog", "stable"):
            raise RuntimeError("Unexpected stable feed scope")
        if any(release["sequence"] >= state["build"] for release in payload["releases"]):
            raise RuntimeError("The proposed build has already been published; synchronize version.properties first")


def publish_source(state):
    run("git", "fetch", "origin", "main")
    remote = git("rev-parse", "origin/main")
    if ancestor(remote, state["source"]):
        run("git", "push", "origin", state["source"] + ":refs/heads/main")
    elif not ancestor(state["source"], remote) or any(p != RECORD for p in git("diff", "--name-only", state["source"], remote).splitlines()):
        raise RuntimeError("Remote source changed; refusing to publish an outdated build")


def check_renewal():
    runs = json.loads(run("gh", "run", "list", "--repo", REPOSITORY, "--workflow", "renew-update-feed.yml",
                          "--limit", "100", "--json", "status", capture=True))
    if any(item["status"] != "completed" for item in runs):
        raise RuntimeError("The feed renewal workflow is active; wait for it to finish, then resume")


def find_github_release(tag):
    # Listing includes authenticated drafts; the by-tag endpoint only promises
    # published releases. Pagination also makes retries independent of age.
    pages = json.loads(run("gh", "api", f"repos/{REPOSITORY}/releases?per_page=100",
                           "--paginate", "--slurp", capture=True))
    return next((release for page in pages for release in page if release["tag_name"] == tag), None)


def wait_for_github_release(tag, published=False):
    # GitHub's release listing can briefly lag successful create/edit requests.
    # Retry reads only; never repeat a create just because its result is absent.
    for attempt in range(6):
        release = find_github_release(tag)
        if release is not None and (not published or not release["draft"]):
            return release
        if attempt < 5:
            delay = 2 ** attempt
            print(f"Waiting for GitHub to show {'the published release' if published else 'the draft'}; retrying in {delay}s...", flush=True)
            time.sleep(delay)
    raise RuntimeError("GitHub has not yet returned the expected release state; resume this run to retry")


def check_github_tag(state, required=False):
    response = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/git/ref/tags/{state['tag']}"],
                              cwd=ROOT, text=True, capture_output=True)
    if response.returncode:
        if not required and "(HTTP 404)" in response.stderr:
            return
        raise RuntimeError("Cannot verify GitHub tag: " + response.stderr.strip())
    ref = json.loads(response.stdout)
    if ref["object"]["type"] != "commit" or ref["object"]["sha"] != state["source"]:
        raise RuntimeError("GitHub tag already points to a different source")


def github_release(directory, state, dmg):
    tag = state["tag"]
    release = wait_for_github_release(tag) if state.get("github_draft_created") else find_github_release(tag)
    check_github_tag(state, required=bool(release and not release["draft"]))
    body = state["notes"].rstrip() + f"\n\nSource: `{state['source']}`\nBuild: {state['build']} (macOS Apple Silicon)\n"
    notes = directory / "github-notes.md"
    notes.write_text(body, encoding="utf-8")
    if release is None:
        run("gh", "release", "create", tag, "--repo", REPOSITORY, "--draft", "--target", state["source"],
            "--title", f"QuotaDog {state['version']} (macOS)", "--notes-file", notes)
        state["github_draft_created"] = True
        save(directory, state)
        release = wait_for_github_release(tag)
    if release["target_commitish"] != state["source"] or (release.get("body") or "").replace("\r\n", "\n").strip() != body.strip():
        raise RuntimeError("Existing GitHub Release has different source or notes; refusing to replace it")
    name = f"QuotaDog-{state['version']}-macos-aarch64.dmg"
    assets = release["assets"]
    if any(asset["name"] != name for asset in assets) or len(assets) > 1:
        raise RuntimeError("Unexpected assets in the macOS GitHub Release")
    if not assets:
        distribution = directory / "distribution"
        distribution.mkdir(exist_ok=True)
        asset = distribution / name
        shutil.copyfile(dmg, asset)
        run("gh", "release", "upload", tag, asset, "--repo", REPOSITORY)
    # Read back both new and previously uploaded assets before publishing.
    with tempfile.TemporaryDirectory(prefix="quotadog-release-asset-") as temporary:
        run("gh", "release", "download", tag, "--repo", REPOSITORY, "--pattern", name, "--dir", temporary)
        if digest(Path(temporary) / name) != state["sha256"]:
            raise RuntimeError("GitHub asset differs; it will not be overwritten")
    if release["draft"]:
        run("gh", "release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--latest")
    wait_for_github_release(tag, published=True)
    check_github_tag(state, required=True)


def publication_status(state, stream=None):
    stream = stream or sys.stdout
    done = state.get("done", [])
    feed = "Publish update feed" in done
    website = "Deploy website" in done
    github = "Publish GitHub Release" in done
    github_requested = state.get("github", False)
    if state.get("complete"):
        overall = "SUCCESS: release publication is complete."
    elif feed and website and (github or not github_requested):
        overall = "PUBLISHED: distribution steps completed; local synchronization is incomplete."
    elif feed or website or github:
        overall = "PARTIALLY PUBLISHED: completed publication steps remain live."
    elif state.get("publication_started"):
        overall = "PUBLICATION INCOMPLETE: remote changes may have succeeded; resume to verify."
    else:
        overall = "NOT PUBLISHED: release preparation is incomplete or publication was deferred."
    print(f"\n{overall}", file=stream, flush=True)
    print("  App download / update feed: " + ("published and verified" if feed else "not confirmed published"), file=stream)
    print("  Website: " + ("deployed" if website else "not confirmed deployed"), file=stream)
    print("  GitHub Release: " + ("published and verified" if github else "not confirmed published" if github_requested else "not requested"), file=stream)
    if state.get("active_step"):
        print("  Stopped at: " + state["active_step"], file=stream)


def summary(state, directory=None):
    config = properties(ROOT / "updater.properties")
    print(f"\nQuotaDog {state['version']} (build {state['build']}, Apple Silicon)")
    if state.get("complete"):
        print("This release has already been published. Details of the published artifact:")
    elif state.get("source"):
        print("Release metadata is committed. This run reuses the prepared source and completed build steps.")
    else:
        print("Shared version/build, CHANGELOG.md and the website will be committed and pushed to origin/main.")
    print(f"Update origin: {config['baseUrl']}\nNotes language: {state['language']}")
    print("GitHub Release: " + (state["tag"] if state["github"] else "disabled"))
    print("\n" + state["notes"])
    if directory:
        print(f"Source: {state.get('source', state['base'])}\nRun directory: {directory}")
    if "sha256" in state:
        print(f"DMG: {state['dmg']}\nSize: {state['size']:,} bytes\nSHA-256: {state['sha256']}")


def release(directory, state, args, env):
    prepare_commit(directory, state)
    notes = directory / "notes.md"
    notes.write_text(state["notes"], encoding="utf-8")
    env = dict(env, QUOTADOG_RELEASES_DIR=str(directory / "artifacts"))
    app = directory / "artifacts/QuotaDog.app"

    def step(name, action, repeat=False):
        if name in state["done"] and not repeat:
            print(f"Already completed: {name}", flush=True)
            return
        check_source(state)
        print(f"\n== {name} ==", flush=True)
        state["active_step"] = name
        save(directory, state)
        action()
        if name not in state["done"]:
            state["done"].append(name)
        state.pop("active_step", None)
        save(directory, state)

    step("Build and sign app", lambda: run("bash", "scripts/build_release.sh", env=env))
    if "Package DMG" not in state["done"]:
        run("codesign", "--verify", "--deep", "--strict", app)
    step("Notarize app", lambda: run(sys.executable, "scripts/notarize_macos.py", app, env=env))
    step("Package DMG", lambda: run("bash", "scripts/build_release_dmg.sh", "--skip-build", "--skip-notarize", env=env))
    if "dmg" not in state:
        packages = list((directory / "artifacts").glob("*.dmg"))
        if len(packages) != 1:
            raise RuntimeError("Expected exactly one DMG in the release output directory")
        state["dmg"] = str(packages[0])
        save(directory, state)
    dmg = Path(state["dmg"])
    step("Notarize DMG", lambda: run(sys.executable, "scripts/notarize_macos.py", dmg, env=env))
    config = properties(ROOT / "updater.properties")
    if "sha256" in state and (digest(dmg) != state["sha256"] or dmg.stat().st_size != state["size"]):
        raise RuntimeError("DMG changed since verification; it will not be published")
    if inspect_dmg(dmg, config) != (state["build"], state["version"], "macos-aarch64"):
        raise RuntimeError("DMG identity does not match this release")
    state.update(sha256=digest(dmg), size=dmg.stat().st_size)
    save(directory, state)
    publisher = [sys.executable, "scripts/publish_update.py", "--dmg", dmg, "--notes", notes,
                 "--notes-language", state["language"], "--private-key", state["private_key"], "--allow-existing"]
    # Always recheck against the live feed, including when resuming a partially published run.
    run(*publisher, env=env)
    summary(state, directory)
    if args.prepare_only:
        print("Prepared and verified. This invocation stopped before pushing or deploying.")
        return
    if not confirm("Publish this DMG, update feed, website and source now?", args.yes):
        print("Publication deferred; the prepared release is available to resume.")
        return
    check_source(state)
    if digest(dmg) != state["sha256"] or dmg.stat().st_size != state["size"]:
        raise RuntimeError("DMG changed while awaiting confirmation; it will not be published")
    check_renewal()
    state["publication_started"] = True
    save(directory, state)
    # Check remote provenance even when the push previously succeeded.
    publish_source(state)
    step("Publish update feed", lambda: run(*publisher, "--publish", env=env), repeat=True)
    # Deployment is safely repeatable if the process stops before saving its checkpoint.
    step("Deploy website", lambda: run("wrangler", "deploy", "--config", "infra/cloudflare/wrangler.jsonc", env=env), repeat=True)
    with tempfile.TemporaryDirectory(prefix="quotadog-website-") as temporary:
        page = Path(temporary) / "index.html"
        origin = config["baseUrl"].removesuffix("/updates")
        if not fetch(origin + "/", page, maximum=1048576) or page.read_bytes() != (ROOT / SITE).read_bytes():
            raise RuntimeError("Deployed website does not match the prepared download page")
    # Audit is idempotent, and records the latest feed if a renewal has since occurred.
    run(sys.executable, "scripts/record_update_feed.py", env=env)
    if state["github"]:
        step("Publish GitHub Release", lambda: github_release(directory, state, dmg))
    run("git", "fetch", "origin", "main")
    remote = git("rev-parse", "origin/main")
    if not ancestor(state["source"], remote) or any(p != RECORD for p in git("diff", "--name-only", state["source"], remote).splitlines()):
        raise RuntimeError("Release published, but main changed; synchronize your checkout manually")
    run("git", "merge", "--ff-only", "origin/main")
    state["complete"] = True
    save(directory, state)
    print(f"\nPublished and verified QuotaDog {state['version']} ({state['build']}): {origin}")
    if state["github"]:
        print(f"https://github.com/{REPOSITORY}/releases/tag/{state['tag']}")
    publication_status(state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", type=version_name)
    parser.add_argument("--notes", type=Path, help="UTF-8 Markdown file; otherwise prompted")
    parser.add_argument("--notes-language", type=notes_language, help="For example en or zh-CN; otherwise prompted")
    parser.add_argument("--github-release", choices=("yes", "no"), help="Whether to publish the same DMG to GitHub; otherwise prompted")
    parser.add_argument("--private-key", type=Path, default=DEFAULT_KEY)
    parser.add_argument("--resume", type=Path, help="Resume a saved run directory without another version bump")
    parser.add_argument("--dry-run", action="store_true", help="Print the plan only; no writes, credential checks or builds")
    parser.add_argument("--prepare-only", action="store_true", help="Commit, build, notarize and verify; stop before pushing or publishing")
    parser.add_argument("--yes", action="store_true", help="Accept preparation and final publication; all input fields must be explicit in non-TTY runs")
    args = parser.parse_args()
    if args.resume and any((args.version, args.notes, args.notes_language, args.github_release, args.private_key != DEFAULT_KEY)):
        parser.error("--resume uses the saved version, notes, GitHub choice and key path; omit those options")
    directory = args.resume.expanduser().resolve() if args.resume else None
    state = None
    try:
        if not directory and not args.dry_run:
            assert_clean()
        state = json.loads((directory / "state.json").read_text(encoding="utf-8")) if directory else collect(args)
        if directory and (state.get("schema") != 1 or state.get("root") != str(ROOT)):
            raise RuntimeError("This release state belongs to another checkout or script version")
        summary(state, directory)
        if args.dry_run:
            if not directory:
                plan_files(state)
            print("Dry run only: no files changed, no commands published anything.")
            return
        if state.get("complete"):
            print("This release run is already complete.")
            publication_status(state)
            return
        RUNS.mkdir(parents=True, exist_ok=True)
        with (RUNS / "publish.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if not directory:
                assert_clean()
            elif "source" in state:
                check_source(state)
            env = preflight(state)
            if not directory:
                check_feed(state)
                if not confirm("Commit the release metadata and build the notarized DMG?", args.yes):
                    print("No release prepared.")
                    return
                directory = RUNS / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6])
                directory.mkdir(mode=0o700)
                state.update(schema=1, root=str(ROOT), base=git("rev-parse", "HEAD"), files=plan_files(state), done=[])
                save(directory, state)
            release(directory, state, args, env)
    except (RuntimeError, OSError, ValueError, KeyError, argparse.ArgumentTypeError, subprocess.CalledProcessError, EOFError, KeyboardInterrupt) as error:
        print(f"\nRelease stopped: {error or 'interrupted'}", file=sys.stderr)
        if state and "done" in state:
            publication_status(state, stream=sys.stderr)
        return 1
    finally:
        if directory and (directory / "state.json").is_file():
            print(f"\nSaved release: {directory}", flush=True)
            if not state or not state.get("complete"):
                command = shlex.join([sys.executable, "scripts/publish_macos.py", "--resume", str(directory)])
                print(f"Resume: {command}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
