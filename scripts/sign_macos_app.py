#!/usr/bin/env python3
"""Embed the pinned updater helper and sign all nested code before sealing the app.

Native JAR signing follows kmp-updater's notarized acceptance implementation
(Apache-2.0; see third_party/kmp-updater/LICENSE).
"""
import argparse
import hashlib
from pathlib import Path
import plistlib
import shutil
import subprocess
import tempfile
import zipfile
from updater_tools import ROOT, tool

MACH_O = {bytes.fromhex(x) for x in ("cffaedfe", "feedfacf", "cefaedfe", "feedface", "cafebabe", "bebafeca")}


def run(*args):
    result = subprocess.run([str(x) for x in args], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"{args[0]} failed: {result.stdout}{result.stderr}")
    return result.stdout


def require_unsigned_jar(archive, jar):
    if any(
        name.upper().startswith("META-INF/")
        and name.upper().endswith((".SF", ".RSA", ".DSA", ".EC"))
        for name in archive.namelist()
    ):
        raise RuntimeError(f"Cannot rewrite a signed dependency JAR: {jar.name}")


def prune_foreign_skiko(app, executable):
    """Drop only the opposite macOS Skiko architecture, before code signing.

    Skiko's macOS runtime JAR can contain both architectures. Compose extracts
    the current one next to the JAR but leaves the other one inside it. Infer
    the target from the launcher (not the build host), and require its extracted
    library to exist with the correct architecture before removing anything.
    Universal launchers retain both architectures.
    """
    architectures = set(run("lipo", "-archs", executable).split())
    if architectures not in ({"arm64"}, {"x86_64"}):
        return
    target = "arm64" if architectures == {"arm64"} else "x64"
    foreign = "x64" if target == "arm64" else "arm64"
    foreign_name = f"libskiko-macos-{foreign}.dylib"
    app_files = app / "Contents/app"
    for jar in app_files.glob("*.jar"):
        with zipfile.ZipFile(jar) as archive:
            removed = {n for n in (foreign_name, foreign_name + ".sha256") if n in archive.namelist()}
            if foreign_name not in removed:
                continue
            retained = app_files / f"libskiko-macos-{target}.dylib"
            if not retained.is_file() or set(run("lipo", "-archs", retained).split()) != architectures:
                raise RuntimeError("Cannot trim Skiko without the matching extracted native library")
            require_unsigned_jar(archive, jar)
            with tempfile.TemporaryDirectory(prefix="quotadog-trim-") as directory:
                updated = Path(directory) / "trimmed.jar"
                with zipfile.ZipFile(updated, "w") as output:
                    output.comment = archive.comment
                    for entry in archive.infolist():
                        if entry.filename not in removed:
                            output.writestr(entry, archive.read(entry))
                shutil.copy2(updated, jar.with_suffix(".trimmed"))
        jar.with_suffix(".trimmed").replace(jar)
        print(f"Removed unused {foreign} Skiko library from {jar.name}", flush=True)


def sign_app(app, identity):
    app = Path(app).resolve(strict=True)
    info = plistlib.loads((app / "Contents/Info.plist").read_bytes())
    sequence = info.get("KMPUpdaterRelease")
    if not isinstance(sequence, str) or not sequence.isdecimal() or int(sequence) <= 0:
        raise RuntimeError("App must embed a positive KMPUpdaterRelease string before signing")
    prune_foreign_skiko(app, app / "Contents/MacOS" / info["CFBundleExecutable"])
    helper = tool("helper")
    destination = app / "Contents/Helpers/kmp-updater-helper"
    destination.parent.mkdir(exist_ok=True)
    shutil.copy2(helper, destination)
    destination.chmod(0o755)
    notices = app / "Contents/Resources/ThirdParty"
    notices.mkdir(parents=True, exist_ok=True)
    shutil.copy2(helper.parent / "LICENSE", notices / "kmp-updater-LICENSE.txt")
    if identity is None:
        return
    entitlements = ROOT / "scripts/macos-entitlements.plist"

    def sign(path, executable=False):
        args = ["codesign", "--force", "--sign", identity, "--options", "runtime", "--timestamp"]
        if executable: args += ["--entitlements", entitlements]
        run(*args, path)

    # Apple scans native libraries inside JARs too. Preserve companion checksums
    # used by Compose's native loader after signing those libraries.
    for jar in (app / "Contents/app").glob("*.jar"):
        with zipfile.ZipFile(jar) as archive:
            native = [n for n in archive.namelist() if n.endswith((".dylib", ".jnilib"))
                      and archive.read(n)[:4] in MACH_O]
            if not native: continue
            require_unsigned_jar(archive, jar)
            with tempfile.TemporaryDirectory(prefix="quotadog-sign-") as directory:
                temporary = Path(directory)
                replacements = {}
                for index, name in enumerate(native):
                    library = temporary / f"{index}.dylib"
                    library.write_bytes(archive.read(name))
                    sign(library)
                    replacements[name] = library.read_bytes()
                    if name + ".sha256" in archive.namelist():
                        replacements[name + ".sha256"] = hashlib.sha256(replacements[name]).hexdigest().encode()
                updated = temporary / "signed.jar"
                with zipfile.ZipFile(updated, "w") as output:
                    for entry in archive.infolist():
                        output.writestr(entry, replacements.get(entry.filename, archive.read(entry)))
                shutil.copy2(updated, jar.with_suffix(".signed"))
        jar.with_suffix(".signed").replace(jar)
    for path in sorted(app.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if not path.is_file() or path.is_symlink(): continue
        # Signing the main executable implicitly seals its bundle. Leave it until
        # the final app signing pass, after the helper and runtime are signed.
        if path == app / "Contents/MacOS" / info["CFBundleExecutable"]: continue
        with path.open("rb") as stream: magic = stream.read(4)
        if magic not in MACH_O: continue
        kind = run("file", "-b", path)
        if "Mach-O" in kind:
            sign(path, "executable" in kind and path.name != "kmp-updater-helper")
    sign(app / "Contents/runtime")
    sign(app, executable=True)
    run("codesign", "--verify", "--deep", "--strict", app)
    print(f"Embedded helper and signed {app.name}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--identity")
    group.add_argument("--unsigned", action="store_true")
    args = parser.parse_args()
    sign_app(args.app, args.identity)
