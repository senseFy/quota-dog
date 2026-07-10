.DEFAULT_GOAL := help

GRADLE ?= ./gradlew
ARGS ?=

.PHONY: help tasks clean test test-shared test-desktop run desktop-run desktop-package android-debug android-install release-apk release-aab release-app release-dmg release-dmg-local release-dmg-unsigned version-current version-bump git-build-info

help: ## Show this help.
	@printf "QuotaDog commands:\n\n"
	@awk 'BEGIN { FS = ":.*##" } /^[a-zA-Z0-9_.-]+:.*##/ { printf "  %-22s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

tasks: ## List all Gradle tasks.
	@$(GRADLE) tasks --all

clean: ## Remove Gradle build outputs.
	@$(GRADLE) clean

test: test-shared ## Run the default test suite.

test-shared: ## Run shared multiplatform unit tests.
	@$(GRADLE) :shared:allTests

test-desktop: ## Run shared desktop unit tests.
	@$(GRADLE) :shared:desktopTest

run: desktop-run ## Run the desktop app.

desktop-run: ## Run the Compose desktop app.
	@$(GRADLE) :composeApp:run

desktop-package: ## Build a desktop package for the current OS.
	@$(GRADLE) :composeApp:packageDistributionForCurrentOS

android-debug: ## Build an Android debug APK.
	@$(GRADLE) :composeApp:assembleDebug

android-install: ## Install the Android debug APK on a connected device.
	@$(GRADLE) :composeApp:installDebug

release-apk: ## Build an Android release APK; requires signing env vars.
	@$(GRADLE) :composeApp:assembleRelease

release-aab: ## Build an Android release AAB; requires signing env vars.
	@$(GRADLE) :composeApp:bundleRelease

release-app: ## Build a Developer ID–signed macOS .app into releases/.
	@./scripts/build_release.sh

release-dmg: ## Build, sign, notarize, and staple a macOS DMG (Saytive cert + saytive-notary).
	@./scripts/build_release_dmg.sh

release-dmg-local: ## Build a signed macOS DMG; skip notarization.
	@./scripts/build_release_dmg.sh --skip-notarize

release-dmg-unsigned: ## Build an unsigned macOS DMG for local testing.
	@./scripts/build_release_dmg.sh --skip-codesign --skip-notarize

version-current: ## Print VERSION_NAME and VERSION_CODE from version.properties.
	@./scripts/bump_version.sh --print-current

version-bump: ## Bump version; pass ARGS='--set-version 1.2.0' (or --bump-code).
	@./scripts/bump_version.sh $(ARGS)

git-build-info: ## Print git build metadata used in release artifact names.
	@./scripts/git_build_info.sh .
