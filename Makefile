.DEFAULT_GOAL := help

SHELL := /bin/bash

GRADLE ?= ./gradlew
ARGS ?=

ANDROID_RELEASE_SCRIPT ?= ./scripts/release-android.sh
ANDROID_VERSION_SCRIPT ?= ./scripts/android-version.sh
ANDROID_RELEASE_ENV ?= $(HOME)/.config/quotadog/android-release.env
ANDROID_PLAY_AAB ?= composeApp/build/outputs/bundle/release/composeApp-release.aab
ANDROID_PLAY_PACKAGE_NAME ?= saien.quotadog
ANDROID_PLAY_TRACK ?= internal
ANDROID_PLAY_RELEASE_STATUS ?= completed
ANDROID_PLAY_RELEASE_NAME ?=
ANDROID_PLAY_RELEASE_NOTES ?=
ANDROID_PLAY_RELEASE_NOTES_LANGUAGE ?= en-US
ANDROID_PLAY_USER_FRACTION ?=
ANDROID_PLAY_CONFIRM_PRODUCTION ?=
ANDROID_PLAY_DRY_RUN ?=
UPLOAD ?=

IOS_RELEASE_SCRIPT ?= ./scripts/release-ios.sh
IOS_VERSION_SCRIPT ?= ./scripts/ios-version.sh
IOS_BUNDLE_ID ?= saien.quotadog
IOS_TEAM_ID ?= $(shell sed -n 's/^TEAM_ID=//p' iosApp/Configuration/Config.xcconfig)
IOS_RELEASE_PROFILE ?=
IOS_SIGNING_CERTIFICATE ?= Apple Distribution
IOS_APP_STORE_CONNECT_APP_ID ?=
IOS_ARCHIVE_PATH ?=
IOS_EXPORT_PATH ?=
IOS_BUILD_NUMBER ?=
IOS_CLEAN ?=
IOS_VERBOSE ?=
APP_STORE_CONNECT_API_KEY_PATH ?=
APP_STORE_CONNECT_API_KEY_ID ?=
APP_STORE_CONNECT_API_ISSUER_ID ?=
ASC_KEY_PATH ?=
ASC_KEY_ID ?=
ASC_ISSUER_ID ?=
EXPO_ASC_API_KEY_PATH ?=
EXPO_ASC_KEY_ID ?=
EXPO_ASC_ISSUER_ID ?=

PUBLISH_TRACKS_SCRIPT ?= ./scripts/publish-tracks.sh
MOBILE_RELEASE_ARGS ?=
MOBILE_RELEASE_LOG_ROOT ?=

IOS_ASC_KEY_PATH := $(or $(APP_STORE_CONNECT_API_KEY_PATH),$(ASC_KEY_PATH),$(EXPO_ASC_API_KEY_PATH))
IOS_ASC_KEY_ID := $(or $(APP_STORE_CONNECT_API_KEY_ID),$(ASC_KEY_ID),$(EXPO_ASC_KEY_ID))
IOS_ASC_ISSUER_ID := $(or $(APP_STORE_CONNECT_API_ISSUER_ID),$(ASC_ISSUER_ID),$(EXPO_ASC_ISSUER_ID))
IOS_RELEASE_ARGS = --team "$(IOS_TEAM_ID)" --bundle-id "$(IOS_BUNDLE_ID)"
IOS_RELEASE_ARGS += --profile "$(IOS_RELEASE_PROFILE)"
IOS_RELEASE_ARGS += --signing-certificate "$(IOS_SIGNING_CERTIFICATE)"
IOS_RELEASE_ARGS += --app-store-connect-app-id "$(IOS_APP_STORE_CONNECT_APP_ID)"
IOS_RELEASE_ARGS += $(if $(IOS_ARCHIVE_PATH),--archive-path "$(abspath $(IOS_ARCHIVE_PATH))",)
IOS_RELEASE_ARGS += $(if $(IOS_EXPORT_PATH),--export-path "$(abspath $(IOS_EXPORT_PATH))",)
IOS_RELEASE_ARGS += $(if $(IOS_BUILD_NUMBER),--build-number "$(IOS_BUILD_NUMBER)",)
IOS_RELEASE_ARGS += $(if $(IOS_ASC_KEY_PATH),--auth-key-path "$(abspath $(IOS_ASC_KEY_PATH))",)
IOS_RELEASE_ARGS += $(if $(IOS_ASC_KEY_ID),--auth-key-id "$(IOS_ASC_KEY_ID)",)
IOS_RELEASE_ARGS += $(if $(IOS_ASC_ISSUER_ID),--auth-key-issuer-id "$(IOS_ASC_ISSUER_ID)",)
IOS_RELEASE_ARGS += $(if $(filter yes y true 1,$(IOS_CLEAN)),--clean,)
IOS_RELEASE_ARGS += $(if $(filter yes y true 1,$(IOS_VERBOSE)),--verbose,)

ANDROID_UPLOAD_ENABLED := $(if $(filter yes y true 1,$(UPLOAD)),yes,)
ANDROID_RELEASE_ENVIRONMENT = \
	ANDROID_RELEASE_ENV="$(ANDROID_RELEASE_ENV)" \
	ANDROID_PLAY_AAB_PATH="$(abspath $(ANDROID_PLAY_AAB))" \
	ANDROID_PLAY_PACKAGE_NAME="$(ANDROID_PLAY_PACKAGE_NAME)" \
	ANDROID_PLAY_TRACK="$(ANDROID_PLAY_TRACK)" \
	ANDROID_PLAY_RELEASE_STATUS="$(ANDROID_PLAY_RELEASE_STATUS)" \
	ANDROID_PLAY_RELEASE_NAME="$(ANDROID_PLAY_RELEASE_NAME)" \
	ANDROID_PLAY_RELEASE_NOTES="$(ANDROID_PLAY_RELEASE_NOTES)" \
	ANDROID_PLAY_RELEASE_NOTES_LANGUAGE="$(ANDROID_PLAY_RELEASE_NOTES_LANGUAGE)" \
	ANDROID_PLAY_USER_FRACTION="$(ANDROID_PLAY_USER_FRACTION)" \
	ANDROID_PLAY_CONFIRM_PRODUCTION="$(ANDROID_PLAY_CONFIRM_PRODUCTION)" \
	ANDROID_PLAY_DRY_RUN="$(ANDROID_PLAY_DRY_RUN)"

.PHONY: help tasks clean test test-shared test-desktop run desktop-run desktop-package android-debug android-install release-apk release-aab release-app release-dmg release-dmg-local release-dmg-unsigned version-current version-bump git-build-info build-identity android-version android-release-check android-upload-check android-release-play android-upload-play android-play-dry-run ios-version ios-release-check ios-upload-check ios-archive ios-release ios-upload-testflight ios-upload-archive ios-export publish-tracks test-mobile-release

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

version-bump: ## Bump patch + code; pass ARGS='--bump-code' for code only.
	@./scripts/bump_version.sh $(ARGS)

git-build-info: ## Print git build metadata used in release artifact names.
	@./scripts/git_build_info.sh .

build-identity: ## Print the immutable source identity embedded in store artifacts.
	@./scripts/lib/build-identity.sh resolve .

android-version: ## Print Android versionName and versionCode.
	@$(ANDROID_VERSION_SCRIPT) show

android-release-check: ## Verify Android signing prerequisites.
	@$(ANDROID_RELEASE_ENVIRONMENT) $(ANDROID_RELEASE_SCRIPT) check

android-upload-check: ## Verify Android signing and Google Play credentials.
	@$(ANDROID_RELEASE_ENVIRONMENT) $(ANDROID_RELEASE_SCRIPT) check-upload

android-release-play: ## Build and verify a signed Play AAB (UPLOAD=yes to publish).
	@$(ANDROID_RELEASE_ENVIRONMENT) $(ANDROID_RELEASE_SCRIPT) \
		$(if $(ANDROID_UPLOAD_ENABLED),upload,build)

android-upload-play: ## Verify and upload the existing signed AAB.
	@$(ANDROID_RELEASE_ENVIRONMENT) $(ANDROID_RELEASE_SCRIPT) upload-existing

android-play-dry-run: ## Verify the existing AAB and print the Play release plan.
	@$(ANDROID_RELEASE_ENVIRONMENT) ANDROID_PLAY_DRY_RUN=yes \
		$(ANDROID_RELEASE_SCRIPT) upload-existing

ios-version: ## Print the iOS marketing version and build number.
	@$(IOS_VERSION_SCRIPT) show

ios-release-check: ## Verify iOS signing and release prerequisites.
	@$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --check

ios-upload-check: ## Verify iOS signing and TestFlight upload prerequisites.
	@$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --upload --check

ios-archive: ## Create and verify a signed Release archive.
	@$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --archive-only

ios-release: ## Archive and export a local App Store Connect IPA.
	@$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS)

ios-upload-testflight: ## Archive and upload a new build to TestFlight.
	@$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --upload

ios-upload-archive: ## Upload an existing verified archive to TestFlight.
	@$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --export-only --upload

ios-export: ## Export an existing verified archive to a local IPA.
	@$(IOS_RELEASE_SCRIPT) $(IOS_RELEASE_ARGS) --export-only

publish-tracks: ## Prepare and publish Play internal and TestFlight builds.
	@QUOTADOG_RELEASE_LOG_ROOT="$(MOBILE_RELEASE_LOG_ROOT)" \
		QUOTADOG_ANDROID_TRACK=internal \
		$(PUBLISH_TRACKS_SCRIPT) $(MOBILE_RELEASE_ARGS)

test-mobile-release: ## Run mobile release contract tests.
	@./scripts/tests/mobile-release-test.sh
