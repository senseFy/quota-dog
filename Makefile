.DEFAULT_GOAL := help

GRADLE ?= ./gradlew

.PHONY: help tasks clean test test-shared test-desktop run desktop-run desktop-package android-debug android-install release-apk release-aab

help: ## Show this help.
	@printf "QuotaDog commands:\n\n"
	@awk 'BEGIN { FS = ":.*##" } /^[a-zA-Z0-9_.-]+:.*##/ { printf "  %-18s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)

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
