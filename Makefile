.PHONY: build clean install install-debug install-release test help

# Default target
help:
	@echo "Available targets:"
	@echo "  build          - Build debug APK"
	@echo "  build-release  - Build release APK"
	@echo "  clean          - Clean build artifacts"
	@echo "  install        - Install debug APK to connected device"
	@echo "  install-release- Install release APK to connected device"
	@echo "  uninstall      - Uninstall app from connected device"
	@echo "  test           - Run tests"
	@echo "  lint           - Run lint checks"
	@echo "  assemble       - Build all variants"

# Build debug APK
build:
	./gradlew assembleDebug

# Build release APK
build-release:
	./gradlew assembleRelease

# Build all variants
assemble:
	./gradlew assemble

# Clean build artifacts
clean:
	./gradlew clean

# Install debug APK to connected device
install: build
	./gradlew installDebug

# Install debug APK without building (if already built)
install-debug:
	./gradlew installDebug

# Install release APK to connected device
install-release: build-release
	./gradlew installRelease

# Uninstall app from device
uninstall:
	./gradlew uninstallAll

# Run tests
test:
	./gradlew test

# Run lint checks
lint:
	./gradlew lint

# Run app on connected device
run: install
	adb shell am start -n com.immichframe.immichframe/.MainActivity
