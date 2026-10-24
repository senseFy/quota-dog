#!/usr/bin/env python3
"""Fetch the pinned upstream publisher/helper, verifying archives before extraction."""
import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.1.0-alpha.1"
ARCHIVES = {
    "helper": (f"kmp-updater-{VERSION}-macos-helper.zip", "bb4786d0c364ab1f487df699ce60bac102a3b251cb744f250c0ce038c3a619a7"),
    "publisher": (f"updater-publisher-{VERSION}.zip", "0e7e8625b660cb6a6a30dd45ddbf6381d770d85a77767aa63614057c2b59f2ad"),
}


def tool(kind):
    name, digest = ARCHIVES[kind]
    directory = ROOT / "build/updater-tools" / VERSION / kind
    binary = directory / ("kmp-updater-helper" if kind == "helper" else f"updater-publisher-{VERSION}/bin/updater-publisher")
    if binary.is_file() and (directory / ".verified").is_file() and (directory / ".verified").read_text() == digest:
        return binary
    directory.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=directory.parent) as temporary:
        stage = Path(temporary)
        archive = stage / name
        subprocess.run(["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "3",
                        "--connect-timeout", "15", "--max-time", "300",
                        "--proto", "=https", "--proto-redir", "=https", "--output", str(archive),
                        f"https://github.com/senseFy/kmp-updater/releases/download/v{VERSION}/{name}"], check=True)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
            raise RuntimeError(f"Upstream archive checksum mismatch: {name}")
        extracted = stage / "extracted"
        extracted.mkdir()
        with zipfile.ZipFile(archive) as source:
            for item in source.infolist():
                destination = (extracted / item.filename).resolve()
                if not destination.is_relative_to(extracted.resolve()):
                    raise RuntimeError("Unsafe archive path")
            source.extractall(extracted)
        if directory.exists():
            shutil.rmtree(directory)
        extracted.rename(directory)
        binary.chmod(0o755)
        (directory / ".verified").write_text(digest)
    return binary


if __name__ == "__main__":
    print(tool(sys.argv[1]))
