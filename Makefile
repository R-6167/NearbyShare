.PHONY: help sdk wrapper native apk install test rust

help:
	@echo "NearbyShare make targets:"
	@echo "  make sdk       Install Android SDK cmdline tools (no Studio)"
	@echo "  make wrapper   Bootstrap android/gradlew"
	@echo "  make native    Build Rust .so + UniFFI Kotlin"
	@echo "  make apk       Full debug APK (native + Gradle)"
	@echo "  make install   adb install debug APK"
	@echo "  make test      cargo test"
	@echo "  make rust      cargo check"

sdk:
	./scripts/setup-android-sdk.sh

wrapper:
	./scripts/bootstrap-gradle-wrapper.sh

native:
	./scripts/build-android-native.sh

apk:
	./scripts/build-apk.sh

install:
	./scripts/install-apk.sh

test:
	cargo test

rust:
	cargo check
