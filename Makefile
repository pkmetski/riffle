.DEFAULT_GOAL := help
WRAPPER_JAR := gradle/wrapper/gradle-wrapper.jar
GRADLE_VERSION := 9.4.1

# Auto-detect JAVA_HOME when not already set.
# Prefers Android Studio's bundled JBR so the build works without a
# separate JDK install; falls back to whatever the system resolves.
# Note: wildcard doesn't handle spaces in paths, so we use $(shell test).
ifeq ($(JAVA_HOME),)
  export JAVA_HOME := $(shell test -x "/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java" && \
    echo "/Applications/Android Studio.app/Contents/jbr/Contents/Home")
endif

# Auto-detect ANDROID_HOME from local.properties or the standard Mac location.
ifeq ($(ANDROID_HOME),)
  export ANDROID_HOME := $(shell grep -m1 '^sdk.dir=' local.properties 2>/dev/null | cut -d= -f2-)
  ifeq ($(ANDROID_HOME),)
    export ANDROID_HOME := $(HOME)/Library/Android/sdk
  endif
endif
export PATH := $(ANDROID_HOME)/emulator:$(ANDROID_HOME)/platform-tools:$(PATH)

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

.PHONY: bootstrap
bootstrap: deps wrapper ## Full first-time setup (installs deps, wrapper)

.PHONY: deps
deps: ## Install missing system dependencies (Java, Gradle via Homebrew on macOS)
	@java -version >/dev/null 2>&1 || { \
		echo "Java not found. Installing temurin via Homebrew..."; \
		brew install --cask temurin@17; \
	}
	@echo "Java: $$(java -version 2>&1 | head -1)"

.PHONY: wrapper
wrapper: ## Download Gradle wrapper jar
	@mkdir -p gradle/wrapper
	@if [ ! -f "$(WRAPPER_JAR)" ]; then \
		echo "Downloading Gradle wrapper jar (v$(GRADLE_VERSION))..."; \
		curl -fsSL -o $(WRAPPER_JAR) \
			"https://raw.githubusercontent.com/gradle/gradle/v$(GRADLE_VERSION)/gradle/wrapper/gradle-wrapper.jar"; \
		echo "Downloaded $(WRAPPER_JAR)"; \
	else \
		echo "$(WRAPPER_JAR) already present"; \
	fi

.PHONY: build
build: wrapper ## Build the project
	./gradlew build

.PHONY: test
test: wrapper ## Run all unit tests
	./gradlew test

.PHONY: lint
lint: wrapper ## Run lint checks
	./gradlew lint

.PHONY: check
check: wrapper ## Run build + lint + tests
	./gradlew build lint test

.PHONY: clean
clean: ## Clean build outputs
	./gradlew clean

.PHONY: install
install: wrapper ## Build debug APK and install on connected device
	./gradlew :app:installDebug

AVD_NAME := Harness_Medium_Phone

.PHONY: harness-test
harness-test: wrapper ## Boot "Harness Medium Phone" AVD, run harness tests, then shut it down
	@AVD_CONFIG=$$HOME/.android/avd/$(AVD_NAME).avd/config.ini; \
	echo "Ensuring AVD heap is 1024 MB (was: $$(grep vm.heapSize $$AVD_CONFIG))..."; \
	sed -i '' 's/^vm\.heapSize=.*/vm.heapSize=1024/' "$$AVD_CONFIG"; \
	STALE=$$(for s in $$(adb devices 2>/dev/null | grep emulator | cut -f1); do \
		name=$$(adb -s $$s emu avd name 2>/dev/null | head -1 | tr -d '\r'); \
		[ "$$name" = "$(AVD_NAME)" ] && echo $$s; \
	done); \
	if [ -n "$$STALE" ]; then \
		echo "Killing stale emulator $$STALE..."; \
		adb -s $$STALE emu kill 2>/dev/null || true; \
		until ! adb devices 2>/dev/null | grep -q "$$STALE"; do sleep 2; done; \
	fi; \
	echo "Starting emulator '$(AVD_NAME)'..."; \
	emulator -avd "$(AVD_NAME)" -no-window -no-audio -no-boot-anim -no-snapshot-load \
		&> /tmp/riffle-emulator.log & \
	EMU_PID=$$!; \
	echo "Waiting for emulator to boot (pid $$EMU_PID)..."; \
	SERIAL=""; \
	until [ -n "$$SERIAL" ]; do \
		sleep 2; \
		SERIAL=$$(for s in $$(adb devices | grep emulator | cut -f1); do \
			name=$$(adb -s $$s emu avd name 2>/dev/null | head -1 | tr -d '\r'); \
			[ "$$name" = "$(AVD_NAME)" ] && echo $$s && break; \
		done); \
	done; \
	until [ "$$(adb -s $$SERIAL shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done; \
	echo "Running harness tests on $$SERIAL..."; \
	adb -s $$SERIAL shell pm clear com.riffle.app 2>/dev/null || true; \
	ANDROID_SERIAL=$$SERIAL ./gradlew :app:connectedDebugAndroidTest; \
	TEST_EXIT=$$?; \
	echo "Shutting down emulator..."; \
	adb -s $$SERIAL emu kill; \
	wait $$EMU_PID 2>/dev/null || true; \
	kill -9 $$EMU_PID 2>/dev/null || true; \
	exit $$TEST_EXIT
