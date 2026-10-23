# Dev setup (macOS, Apple Silicon)

Prerequisite: JDK 17.

## Android SDK

```sh
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator

mkdir -p $ANDROID_HOME/cmdline-tools
curl -fLO https://dl.google.com/android/repository/commandlinetools-mac-15641748_latest.zip
unzip commandlinetools-mac-15641748_latest.zip -d /tmp/cmdline-tools-ex
mv /tmp/cmdline-tools-ex/cmdline-tools $ANDROID_HOME/cmdline-tools/latest

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" \
  "emulator" "system-images;android-37.0;google_apis;arm64-v8a"
```

Installed versions: platform-tools 37.0.1, emulator 37.2.12, build-tools 37.0.0, platform android-37.0.

Add the two `export` lines to `~/.zshrc` to persist them.

## Emulator

```sh
echo no | avdmanager create avd -n sa_pixel -d pixel_9 \
  -k "system-images;android-37.0;google_apis;arm64-v8a"
emulator -list-avds            # expect: sa_pixel
$ANDROID_HOME/emulator/emulator -avd sa_pixel
```

## Verify

```sh
adb version && emulator -list-avds
```

## Native engine toolchain (M2)

```sh
sdkmanager "ndk;30.0.16248370" "cmake;4.1.2"
```

The `:engine` module builds with NDK 30.0.16248370 and SDK CMake 4.1.2 (versions in `gradle/libs.versions.toml`).
The host (macOS, Apple clang) GoogleTest suite fetches GoogleTest v1.17.0 into `engine/host/build/` (gitignored) on the
first run, so that run needs network access:

```sh
engine/host/run-tests.sh      # or: ./gradlew :engine:hostTest (also part of ./gradlew check)
```

Release APK checks: `zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk` and
`unzip -l` showing `lib/arm64-v8a/libsaengine.so`.
