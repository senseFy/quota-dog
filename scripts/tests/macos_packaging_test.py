#!/usr/bin/env python3
"""Check safe architecture trimming and native status-bar teardown."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sign_macos_app import prune_foreign_skiko


class SkikoTrimmingTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.app = Path(self.directory.name) / "QuotaDog.app"
        self.files = self.app / "Contents/app"
        self.files.mkdir(parents=True)
        self.executable = self.app / "Contents/MacOS/QuotaDog"
        self.retained = self.files / "libskiko-macos-arm64.dylib"
        self.retained.write_bytes(b"current-architecture")
        self.jar = self.files / "skiko.jar"
        self.localized_resource = "composeResources/quotadog/drawable/图标.svg"
        self.localized_contents = '<svg><title>配额 · クォータ</title></svg>'.encode("utf-8")
        with zipfile.ZipFile(self.jar, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("libskiko-macos-x64.dylib", b"foreign-architecture")
            archive.writestr("libskiko-macos-x64.dylib.sha256", b"foreign-checksum")
            archive.writestr("META-INF/LICENSE", b"retain-license")
            archive.writestr("org/jetbrains/skiko/Library.class", b"retain-loader")
            archive.writestr(self.localized_resource, self.localized_contents)
            archive.comment = b"retain-comment"

    def test_removes_only_foreign_library_and_checksum_idempotently(self):
        with patch("sign_macos_app.run", return_value="arm64"):
            prune_foreign_skiko(self.app, self.executable)
            once = self.jar.read_bytes()
            prune_foreign_skiko(self.app, self.executable)
        self.assertEqual(once, self.jar.read_bytes())
        self.assertEqual(b"current-architecture", self.retained.read_bytes())
        with zipfile.ZipFile(self.jar) as archive:
            self.assertEqual(
                {"META-INF/LICENSE", "org/jetbrains/skiko/Library.class", self.localized_resource},
                set(archive.namelist()),
            )
            self.assertEqual(b"retain-license", archive.read("META-INF/LICENSE"))
            self.assertEqual(b"retain-loader", archive.read("org/jetbrains/skiko/Library.class"))
            self.assertEqual(self.localized_contents, archive.read(self.localized_resource))
            self.assertEqual(b"retain-comment", archive.comment)

    def test_missing_or_wrong_target_library_fails_without_rewriting(self):
        original = self.jar.read_bytes()
        with patch("sign_macos_app.run", side_effect=["arm64", "x86_64"]):
            with self.assertRaisesRegex(RuntimeError, "matching extracted"):
                prune_foreign_skiko(self.app, self.executable)
        self.retained.unlink()
        with patch("sign_macos_app.run", return_value="arm64"):
            with self.assertRaisesRegex(RuntimeError, "matching extracted"):
                prune_foreign_skiko(self.app, self.executable)
        self.assertEqual(original, self.jar.read_bytes())

    def test_universal_launcher_keeps_both_architectures(self):
        original = self.jar.read_bytes()
        with patch("sign_macos_app.run", return_value="arm64 x86_64"):
            prune_foreign_skiko(self.app, self.executable)
        self.assertEqual(original, self.jar.read_bytes())

    def test_signed_dependency_is_rejected_without_rewriting(self):
        with zipfile.ZipFile(self.jar, "a") as archive:
            archive.writestr("META-INF/UPSTREAM.SF", b"signature")
        original = self.jar.read_bytes()
        with patch("sign_macos_app.run", return_value="arm64"):
            with self.assertRaisesRegex(RuntimeError, "signed dependency"):
                prune_foreign_skiko(self.app, self.executable)
        self.assertEqual(original, self.jar.read_bytes())


@unittest.skipUnless(sys.platform == "darwin", "requires AppKit")
class StatusBarLifecycleTest(unittest.TestCase):
    def test_worker_thread_disposal_releases_appkit_objects_on_main_thread(self):
        root = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix="quotadog-statusbar-test-") as directory:
            executable = Path(directory) / "statusbar-test"
            subprocess.run([
                "clang", "-fobjc-arc", "-framework", "AppKit", "-framework", "Foundation",
                str(root / "composeApp/src/desktopMain/native/macos/QuotaDogStatusBar.m"),
                str(Path(__file__).with_name("macos_statusbar_test.m")),
                "-o", str(executable),
            ], check=True, timeout=60)
            subprocess.run([str(executable)], check=True, timeout=30)


if __name__ == "__main__":
    unittest.main()
