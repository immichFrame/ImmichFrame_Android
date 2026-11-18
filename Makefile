.PHONY: build build-release assemble clean install install-debug install-release uninstall test lint run help

# gradlew is not marked executable in the repository, so it is run through sh

# Default target
help:
	@echo "Available targets:"
	@echo "  build           - Build debug APK"
	@echo "  build-release   - Build release APK"
	@echo "  clean           - Clean build artifacts"
	@echo "  install         - Install debug APK to connected device"
	@echo "  install-release - Install release APK to connected device"
	@echo "  uninstall       - Uninstall app from connected device"
	@echo "  test            - Run tests"
	@echo "  lint            - Run lint checks"
	@echo "  assemble        - Build all variants"

# Build debug APK
build:
	sh ./gradlew assembleDebug

# Build release APK
build-release:
	sh ./gradlew assembleRelease

# Build all variants
assemble:
	sh ./gradlew assemble

# Clean build artifacts
clean:
	sh ./gradlew clean

# Install debug APK to connected device
install: build
	sh ./gradlew installDebug

# Install debug APK without building (if already built)
install-debug:
	sh ./gradlew installDebug

# Install release APK to connected device
install-release: build-release
	sh ./gradlew installRelease

# Uninstall app from device
uninstall:
	sh ./gradlew uninstallAll

# Run tests
test:
	sh ./gradlew test

# Run lint checks
lint:
	sh ./gradlew lint

# Run app on connected device
run: install
	adb shell am start -n com.immichframe.immichframe/.MainActivity
