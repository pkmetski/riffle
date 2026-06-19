.DEFAULT_GOAL := help
FONTS_DIR := app/src/main/assets/fonts
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
bootstrap: deps wrapper fonts ## Full first-time setup (installs deps, wrapper, fonts)

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

.PHONY: fonts
fonts: ## Download bundled fonts (Literata, Merriweather, OpenDyslexic — SIL OFL)
	@mkdir -p $(FONTS_DIR)
	@# Literata + Merriweather: variable TTFs from google/fonts repo (single file covers all weights).
	@if [ ! -f "$(FONTS_DIR)/Literata-Regular.ttf" ]; then \
		echo "Downloading Literata..."; \
		curl -fsSL -o "$(FONTS_DIR)/Literata-Regular.ttf" \
			"https://github.com/google/fonts/raw/main/ofl/literata/Literata%5Bopsz%2Cwght%5D.ttf"; \
	fi
	@if [ ! -f "$(FONTS_DIR)/Merriweather-Regular.ttf" ]; then \
		echo "Downloading Merriweather..."; \
		curl -fsSL -o "$(FONTS_DIR)/Merriweather-Regular.ttf" \
			"https://github.com/google/fonts/raw/main/ofl/merriweather/Merriweather%5Bopsz%2Cwdth%2Cwght%5D.ttf"; \
	fi
	@# OpenDyslexic: direct OTF from upstream repo (releases page has no assets).
	@if [ ! -f "$(FONTS_DIR)/OpenDyslexic-Regular.otf" ]; then \
		echo "Downloading OpenDyslexic..."; \
		curl -fsSL -o "$(FONTS_DIR)/OpenDyslexic-Regular.otf" \
			"https://github.com/antijingoist/opendyslexic/raw/main/compiled/OpenDyslexic-Regular.otf"; \
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
install: wrapper fonts ## Build debug APK and install on connected device
	./gradlew :app:installDebug

PHONE_AVD_BASE := Harness_Medium_Phone
TABLET_AVD_BASE := Harness_Medium_Tablet
TABLET_ANNOTATION := com.riffle.app.harness.TabletLayout

# $(1) = base AVD to clone (must be API 25 / Android 7.1.1), $(2) = extra gradle args
# Each invocation clones a fresh ephemeral AVD from the base, runs tests, then deletes it.
# Using a unique name (PID) avoids any interference from parallel workspace runs.
define run_harness_tests
	AVD_BASE="$(1)"; \
	BRANCH=$$(git rev-parse --abbrev-ref HEAD 2>/dev/null | tr -cs '[:alnum:]' '-' | sed 's/-*$$//'); \
	AVD_NAME="Harness_$${BRANCH}_$$$$"; \
	AVD_DIR="$$HOME/.android/avd/$$AVD_NAME.avd"; \
	EMU_PID=""; SERIAL=""; \
	trap 'adb -s "$$SERIAL" emu kill 2>/dev/null || true; wait $$EMU_PID 2>/dev/null || true; kill -9 $$EMU_PID 2>/dev/null || true; rm -rf "$$AVD_DIR" "$$HOME/.android/avd/$$AVD_NAME.ini" 2>/dev/null || true' EXIT INT TERM; \
	echo "Cloning $$AVD_BASE → $$AVD_NAME..."; \
	cp -c -R "$$HOME/.android/avd/$$AVD_BASE.avd" "$$AVD_DIR"; \
	sed "s|/$$AVD_BASE\.avd|/$$AVD_NAME.avd|g; s|$$AVD_BASE\.avd|$$AVD_NAME.avd|g" \
		"$$HOME/.android/avd/$$AVD_BASE.ini" > "$$HOME/.android/avd/$$AVD_NAME.ini"; \
	sed -i '' "s/^AvdId=.*/AvdId=$$AVD_NAME/" "$$AVD_DIR/config.ini"; \
	sed -i '' "s/^avd.ini.displayname=.*/avd.ini.displayname=Harness $${BRANCH} $$$$/" "$$AVD_DIR/config.ini"; \
	sed -i '' 's/^vm\.heapSize=.*/vm.heapSize=1024/' "$$AVD_DIR/config.ini"; \
	rm -rf "$$AVD_DIR/snapshots" "$$AVD_DIR"/*.lock \
		"$$AVD_DIR/snapshot.trace" "$$AVD_DIR/read-snapshot.txt" 2>/dev/null || true; \
	echo "Starting emulator '$$AVD_NAME'..."; \
	emulator -avd "$$AVD_NAME" -no-window -no-audio -no-boot-anim -no-snapshot-load -no-snapshot-save \
		&> "/tmp/riffle-emulator-$$AVD_NAME.log" & \
	EMU_PID=$$!; \
	echo "Waiting for emulator to boot (pid $$EMU_PID)..."; \
	SERIAL=""; \
	until [ -n "$$SERIAL" ]; do \
		sleep 2; \
		SERIAL=$$(for s in $$(adb devices | grep emulator | cut -f1); do \
			name=$$(adb -s $$s emu avd name 2>/dev/null | head -1 | tr -d '\r'); \
			[ "$$name" = "$$AVD_NAME" ] && echo $$s && break; \
		done); \
	done; \
	until [ "$$(adb -s $$SERIAL shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done; \
	adb -s $$SERIAL shell input keyevent KEYCODE_WAKEUP; \
	adb -s $$SERIAL shell wm dismiss-keyguard; \
	adb -s $$SERIAL shell svc power stayon true; \
	echo "Running harness tests on $$SERIAL..."; \
	adb -s $$SERIAL shell pm clear com.riffle.app 2>/dev/null || true; \
	ANDROID_SERIAL=$$SERIAL ./gradlew :app:connectedDebugAndroidTest $(2); \
	TEST_EXIT=$$?; \
	echo "Shutting down emulator..."; \
	adb -s $$SERIAL emu kill; \
	wait $$EMU_PID 2>/dev/null || true; \
	kill -9 $$EMU_PID 2>/dev/null || true; \
	echo "Deleting temp AVD $$AVD_NAME..."; \
	rm -rf "$$AVD_DIR" "$$HOME/.android/avd/$$AVD_NAME.ini"; \
	exit $$TEST_EXIT
endef

.PHONY: harness-test
harness-test: wrapper fonts ## Clone a fresh API-25 phone AVD, run non-tablet harness tests, then delete it
	@$(call run_harness_tests,$(PHONE_AVD_BASE),-Pandroid.testInstrumentationRunnerArguments.notAnnotation=$(TABLET_ANNOTATION))

.PHONY: harness-test-tablet
harness-test-tablet: wrapper fonts ## Clone a fresh API-25 tablet AVD, run @TabletLayout tests only, then delete it
	@$(call run_harness_tests,$(TABLET_AVD_BASE),-Pandroid.testInstrumentationRunnerArguments.annotation=$(TABLET_ANNOTATION))

.PHONY: harness-test-class
harness-test-class: wrapper fonts ## Clone a fresh API-25 phone AVD, run a single test CLASS=<fqcn> (or pkg.Class#method), then delete it
	@$(call run_harness_tests,$(PHONE_AVD_BASE),-Pandroid.testInstrumentationRunnerArguments.class=$(CLASS))
