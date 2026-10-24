#!/usr/bin/env python3
"""Exercise release commits and retries with local Git and fake publishing services."""
import argparse
from contextlib import ExitStack, redirect_stdout
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_macos as release


class MacOSReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "repo"
        self.root.mkdir()
        (self.root / "version.properties").write_text("# Shared version\nVERSION_NAME=1.0.12\nVERSION_CODE=24\n")
        (self.root / "updater.properties").write_text("baseUrl=https://updates.example.test/updates\n")
        site = self.root / release.SITE
        site.parent.mkdir(parents=True)
        site.write_text('<p id="macos-version">Version 1.0.12</p>\n<a id="macos-download" href="/old.dmg">Download</a>\n')
        (self.root / ".gitignore").write_text("build/\n")
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Release Test")
        self.git("config", "user.email", "test@example.test")
        self.git("config", "commit.gpgsign", "false")
        self.git("config", "core.hooksPath", str(self.root / "no-hooks"))
        self.git("add", ".")
        self.git("commit", "-m", "Initial fixture")
        remote = Path(self.temporary.name) / "remote.git"
        subprocess.run(["git", "clone", "--bare", str(self.root), str(remote)], check=True, capture_output=True)
        self.git("remote", "add", "origin", str(remote))
        self.git("fetch", "origin", "main")
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(release, "ROOT", self.root))
        self.stack.enter_context(patch.object(release, "RUNS", self.root / "build/release/macos"))
        self.output = self.stack.enter_context(redirect_stdout(io.StringIO()))
        self.directory = self.root / "build/release/macos/test"
        self.directory.mkdir(parents=True)
        self.state = dict(schema=1, root=str(self.root), version="1.0.13", build=25,
                          notes="- 改进菜单栏退出行为。\n", language="zh-CN", github=False,
                          tag="macos-v1.0.13-build25", private_key="/unused/test.private-key",
                          base=self.git("rev-parse", "HEAD"), done=[])
        self.state["files"] = release.plan_files(self.state)
        release.save(self.directory, self.state)
        self.calls = []
        self.fail_deploy = False
        self.original_run = release.run

    def git(self, *args):
        return subprocess.check_output(["git", *args], cwd=self.root, stderr=subprocess.DEVNULL, text=True).strip()

    def fake_run(self, *command, **kwargs):
        command = [str(value) for value in command]
        self.calls.append(command)
        if command[0] == "git":
            return self.original_run(*command, **kwargs)
        if "scripts/build_release.sh" in command:
            (self.directory / "artifacts/QuotaDog.app").mkdir(parents=True)
        elif "scripts/build_release_dmg.sh" in command:
            (self.directory / "artifacts/release.dmg").write_bytes(b"notarized-package-fixture")
        elif command[:2] == ["wrangler", "deploy"] and self.fail_deploy:
            raise RuntimeError("Simulated deployment interruption after feed publication")

    def fake_fetch(self, url, destination, maximum=262144):
        self.assertEqual(url, "https://updates.example.test/")
        Path(destination).write_bytes((self.root / release.SITE).read_bytes())
        return True

    def run_release(self, prepare_only=False, confirm=True):
        args = argparse.Namespace(prepare_only=prepare_only, yes=False)
        with patch.object(release, "run", self.fake_run), patch.object(release, "inspect_dmg", return_value=(25, "1.0.13", "macos-aarch64")), \
                patch.object(release, "check_renewal"), patch.object(release, "fetch", self.fake_fetch), \
                patch.object(release, "confirm", return_value=confirm):
            release.release(self.directory, self.state, args, os.environ.copy())

    def test_prepare_commits_unicode_notes_but_does_not_publish(self):
        self.run_release(prepare_only=True)
        self.assertEqual(self.git("status", "--porcelain"), "")
        self.assertEqual(self.git("rev-list", "--count", "HEAD"), "2")
        self.assertIn("VERSION_CODE=25", (self.root / "version.properties").read_text())
        self.assertIn(self.state["notes"], (self.root / "CHANGELOG.md").read_text(encoding="utf-8"))
        self.assertIn("/updates/releases/25/QuotaDog-1.0.13-macos-aarch64.dmg", (self.root / release.SITE).read_text())
        self.assertFalse(any("--publish" in command or command[:2] in (["git", "push"], ["wrangler", "deploy"]) for command in self.calls))
        publisher = next(command for command in self.calls if "scripts/publish_update.py" in command)
        self.assertEqual(publisher[publisher.index("--notes-language") + 1], "zh-CN")
        self.assertFalse(self.state["tag"].startswith("v"))

    def test_declining_final_confirmation_keeps_prepared_artifact(self):
        self.run_release(confirm=False)
        self.assertTrue(Path(self.state["dmg"]).is_file())
        self.assertFalse(any("--publish" in command or command[:2] == ["git", "push"] for command in self.calls))

    def test_resume_after_upload_failure_reuses_commit_and_dmg(self):
        self.fail_deploy = True
        with self.assertRaisesRegex(RuntimeError, "deployment interruption"):
            self.run_release()
        source = self.state["source"]
        digest = self.state["sha256"]
        self.assertIn("Publish update feed", self.state["done"])
        self.state = json.loads((self.directory / "state.json").read_text())
        self.fail_deploy = False
        self.run_release()
        self.assertTrue(self.state["complete"])
        self.assertEqual(self.state["source"], source)
        self.assertEqual(self.state["sha256"], digest)
        self.assertEqual(self.git("rev-list", "--count", "HEAD"), "2")
        self.assertEqual(sum("scripts/build_release.sh" in command for command in self.calls), 1)
        self.assertEqual(sum("scripts/build_release_dmg.sh" in command for command in self.calls), 1)
        self.assertEqual(sum("--publish" in command for command in self.calls), 2)

    def test_resume_rejects_modified_artifact_before_any_publish(self):
        self.run_release(prepare_only=True)
        Path(self.state["dmg"]).write_bytes(b"replaced")
        self.calls.clear()
        with self.assertRaisesRegex(RuntimeError, "DMG changed"):
            self.run_release()
        self.assertFalse(any("--publish" in command for command in self.calls))

    def test_resume_rejects_new_source_changes(self):
        self.run_release(prepare_only=True)
        (self.root / "app.txt").write_text("unrelated code")
        self.git("add", "app.txt")
        self.git("commit", "-m", "Change application")
        with self.assertRaisesRegex(RuntimeError, "Source changed"):
            self.run_release()

    def test_commit_checkpoint_failure_does_not_bump_twice(self):
        with patch.object(release, "save", side_effect=OSError("interrupted")):
            with self.assertRaises(OSError):
                release.prepare_commit(self.directory, self.state)
        original = json.loads((self.directory / "state.json").read_text())
        release.prepare_commit(self.directory, original)
        self.assertEqual(self.git("rev-list", "--count", "HEAD"), "2")
        self.assertEqual(original["source"], self.git("rev-parse", "HEAD"))

    def test_partial_preparation_preserves_unexpected_user_edits(self):
        (self.root / "version.properties").write_text(self.state["files"]["version.properties"]["after"])
        (self.root / release.SITE).write_text("user's edit")
        with self.assertRaisesRegex(RuntimeError, "overwrite an edit"):
            release.prepare_commit(self.directory, self.state)
        self.assertEqual((self.root / release.SITE).read_text(), "user's edit")
        self.assertEqual(self.git("rev-list", "--count", "HEAD"), "1")

    def test_dry_run_makes_no_changes_and_needs_no_credentials(self):
        notes = self.directory / "input.md"
        notes.write_text(self.state["notes"], encoding="utf-8")
        arguments = ["publish_macos.py", "--dry-run", "--version", "1.0.13", "--notes", str(notes),
                     "--notes-language", "zh-CN", "--github-release", "yes"]
        with patch.object(sys, "argv", arguments), patch.object(release, "preflight", side_effect=AssertionError("credentials accessed")), \
                patch.object(release, "run", side_effect=AssertionError("external action")):
            release.main()
        self.assertEqual(self.git("rev-parse", "HEAD"), self.state["base"])
        self.assertEqual(self.git("status", "--porcelain"), "")
        self.assertFalse((self.root / "CHANGELOG.md").exists())

    def test_interactive_input_retries_invalid_values_and_accepts_chinese_notes(self):
        args = argparse.Namespace(version=None, notes=None, notes_language=None, github_release=None,
                                  private_key=release.DEFAULT_KEY)
        answers = ["bad-version", "1.0.12", "", "Chinese", "zh-CN", "", "- 修复退出问题。", ".", "maybe", "y"]
        with patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input", side_effect=answers):
            state = release.collect(args)
        self.assertEqual((state["version"], state["build"]), ("1.0.13", 25))
        self.assertEqual(state["language"], "zh-CN")
        self.assertEqual(state["notes"], "- 修复退出问题。\n")
        self.assertTrue(state["github"])

    def test_missing_notes_file_can_be_retried_without_losing_inputs(self):
        notes = self.directory / "release notes.md"
        notes.write_text("Performance optimization\n", encoding="utf-8")
        args = argparse.Namespace(version=None, notes=None, notes_language=None, github_release=None,
                                  private_key=release.DEFAULT_KEY)
        answers = ["1.0.14", "en-US", "2", "Performance optimization", str(notes), "no"]
        with patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input", side_effect=answers):
            state = release.collect(args)
        self.assertEqual(state["version"], "1.0.14")
        self.assertEqual(state["language"], "en-US")
        self.assertEqual(state["notes"], "Performance optimization\n")
        self.assertFalse(state["github"])
        self.assertIn("Could not read release notes", self.output.getvalue())

    def test_bad_notes_file_can_switch_to_direct_input(self):
        bad_file = self.directory / "invalid-utf8.md"
        bad_file.write_bytes(b"\xff")
        answers = ["2", str(bad_file), "", "", "Performance optimization", "."]
        with patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input", side_effect=answers):
            self.assertEqual(release.collect_notes(), "Performance optimization\n")

    def test_empty_notes_prompt_again_and_explicit_cli_file_still_fails_fast(self):
        answers = ["", ".", "", "Performance optimization", "."]
        with patch.object(sys.stdin, "isatty", return_value=True), patch("builtins.input", side_effect=answers):
            self.assertEqual(release.collect_notes(), "Performance optimization\n")
        with patch("builtins.input", side_effect=AssertionError("Unexpected interactive fallback")):
            with self.assertRaises(FileNotFoundError):
                release.collect_notes(self.directory / "missing.md")

    def test_new_github_release_stays_draft_until_asset_is_verified(self):
        dmg = self.directory / "package.dmg"
        dmg.write_bytes(b"notarized-package")
        self.state.update(source="a" * 40, sha256=release.digest(dmg))
        body = self.state["notes"].rstrip() + f"\n\nSource: `{'a' * 40}`\nBuild: 25 (macOS Apple Silicon)\n"
        draft = dict(target_commitish="a" * 40, body=body, draft=True, assets=[])
        commands = []

        def run(*command, **kwargs):
            commands.append(command)
            if command[:3] == ("gh", "release", "download"):
                (Path(command[command.index("--dir") + 1]) / "QuotaDog-1.0.13-macos-aarch64.dmg").write_bytes(dmg.read_bytes())

        with patch.object(release, "find_github_release", side_effect=[None, None, draft, draft, dict(draft, draft=False)]), \
                patch.object(release, "check_github_tag"), patch.object(release, "run", run), \
                patch.object(release.time, "sleep") as sleep:
            release.github_release(self.directory, self.state, dmg)
        self.assertEqual(sleep.call_count, 2)
        self.assertEqual([command[2] for command in commands], ["create", "upload", "download", "edit"])
        self.assertIn("--draft", commands[0])
        self.assertIn("--draft=false", commands[-1])
        self.assertTrue(json.loads((self.directory / "state.json").read_text())["github_draft_created"])

    def test_invisible_created_draft_is_not_created_again_on_resume(self):
        self.state["source"] = "a" * 40
        with patch.object(release, "find_github_release", return_value=None), \
                patch.object(release, "check_github_tag"), patch.object(release, "run") as run, \
                patch.object(release.time, "sleep"):
            with self.assertRaisesRegex(RuntimeError, "resume this run"):
                release.github_release(self.directory, self.state, self.directory / "package.dmg")
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[:3], ("gh", "release", "create"))
            self.state = json.loads((self.directory / "state.json").read_text())
            run.reset_mock()
            with self.assertRaisesRegex(RuntimeError, "resume this run"):
                release.github_release(self.directory, self.state, self.directory / "package.dmg")
            run.assert_not_called()

    def test_status_distinguishes_live_cloudflare_release_from_pending_github(self):
        self.state.update(github=True, done=["Publish update feed", "Deploy website"], active_step="Publish GitHub Release")
        output = io.StringIO()
        release.publication_status(self.state, stream=output)
        self.assertIn("PARTIALLY PUBLISHED", output.getvalue())
        self.assertIn("App download / update feed: published and verified", output.getvalue())
        self.assertIn("Website: deployed", output.getvalue())
        self.assertIn("GitHub Release: not confirmed published", output.getvalue())
        self.assertIn("Stopped at: Publish GitHub Release", output.getvalue())
        self.state["done"].append("Publish GitHub Release")
        self.state.pop("active_step")
        self.state["complete"] = True
        output = io.StringIO()
        release.publication_status(self.state, stream=output)
        self.assertIn("SUCCESS: release publication is complete", output.getvalue())
        self.assertNotIn("not confirmed", output.getvalue())

    def test_completed_run_reports_success_without_suggesting_another_retry(self):
        self.state.update(complete=True, done=["Publish update feed", "Deploy website"])
        release.save(self.directory, self.state)
        with patch.object(sys, "argv", ["publish_macos.py", "--resume", str(self.directory)]), \
                patch.object(release, "preflight", side_effect=AssertionError("Already complete")):
            release.main()
        self.assertIn("SUCCESS: release publication is complete", self.output.getvalue())
        self.assertNotIn("Resume:", self.output.getvalue())
        self.assertNotIn("will be committed and pushed", self.output.getvalue())

    def test_existing_github_asset_is_verified_without_overwriting(self):
        dmg = self.directory / "package.dmg"
        dmg.write_bytes(b"notarized-package")
        self.state.update(source="a" * 40, sha256=release.digest(dmg))
        body = self.state["notes"].rstrip() + f"\n\nSource: `{'a' * 40}`\nBuild: 25 (macOS Apple Silicon)\n"
        name = "QuotaDog-1.0.13-macos-aarch64.dmg"
        published = dict(target_commitish="a" * 40, body=body, draft=False, assets=[dict(name=name)])
        commands = []

        def run(*command, **kwargs):
            commands.append(command)
            if command[:3] == ("gh", "release", "download"):
                (Path(command[command.index("--dir") + 1]) / name).write_bytes(b"notarized-package")

        with patch.object(release, "find_github_release", return_value=published), \
                patch.object(release, "check_github_tag"), patch.object(release, "run", run):
            release.github_release(self.directory, self.state, dmg)
            self.assertEqual(len(commands), 1)
            self.state["sha256"] = "0" * 64
            with self.assertRaisesRegex(RuntimeError, "asset differs"):
                release.github_release(self.directory, self.state, dmg)
        self.assertFalse(any("upload" in command or "edit" in command for command in commands))


if __name__ == "__main__":
    unittest.main()
