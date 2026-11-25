#!/usr/bin/env bash
set -euo pipefail
root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk" ]]; then
  echo 'Set ANDROID_SDK_ROOT to the installed Android SDK.' >&2
  exit 1
fi
tools="${COPY_BUILD_TOOLS:-$sdk/build-tools/37.0.0}"
platform="${COPY_ANDROID_PLATFORM:-$sdk/platforms/android-37.0}"
out="${COPY_BUILD_DIR:-$root/build/manual}"
gradle_setting() {
  awk -v key="$1" '$1 == key { gsub(/"/, "", $2); print $2; exit }' "$root/app/build.gradle"
}
min_sdk="$(gradle_setting minSdk)"
target_sdk="$(gradle_setting targetSdk)"
version_code="$(gradle_setting versionCode)"
version_name="$(gradle_setting versionName)"
application_id="$(gradle_setting applicationId)"
namespace="$(gradle_setting namespace)"
variant="${COPY_BUILD_VARIANT:-test}"
if [[ -z "${COPY_BUILD_VARIANT:-}" && -n "${COPY_SIGNING_KEY:-}" ]]; then
    variant=release
fi
case "$variant" in
    test)
        suffix="$(gradle_setting applicationIdSuffix)"
        if [[ -z "$suffix" ]]; then
            echo 'The Android debug build must define an applicationIdSuffix.' >&2
            exit 1
        fi
        application_id+="$suffix"
        default_apk=binance-copy-terminal-test.apk
        mapping=test-mapping.txt
        ;;
    release)
        if [[ -z "${COPY_SIGNING_KEY:-}" ]]; then
            echo 'Set COPY_SIGNING_KEY to the authorized release signing key.' >&2
            exit 1
        fi
        default_apk=binance-copy-terminal.apk
        mapping=release-mapping.txt
        ;;
    *)
        echo 'Set COPY_BUILD_VARIANT to test or release.' >&2
        exit 1
        ;;
esac
mkdir -p "$out"
work="$(mktemp -d "$out/compile.XXXXXX")"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work"/{classes,generated,dex}
key="${COPY_SIGNING_KEY:-$out/local-signing.p12}"
if [[ ! -f "$key" ]]; then
  if [[ -n "${COPY_SIGNING_KEY:-}" ]]; then
    echo 'The configured signing key is missing.' >&2
    exit 1
  fi
  umask 077
  keytool -genkeypair -storetype PKCS12 -keystore "$key" -storepass android \
    -keyalg RSA -keysize 2048 -alias app -dname 'CN=Local Copy Terminal' -validity 10000
fi
actual_cert="$(keytool -exportcert -keystore "$key" -alias app -storepass android | sha256sum | cut -d ' ' -f 1)"
release_cert="$actual_cert"
if [[ "$variant" == release ]]; then
    release_cert="${COPY_RELEASE_CERT_SHA256:-$(cat "$root/app/release-cert.sha256")}"
fi
if [[ "$actual_cert" != "${release_cert,,}" ]]; then
  echo 'The release signing certificate does not match the expected SHA-256.' >&2
  exit 1
fi
"$tools/aapt2" compile --dir "$root/app/src/main/res" -o "$work/resources.zip"
resources=("$work/resources.zip")
if [[ "$variant" == test ]]; then
    "$tools/aapt2" compile --dir "$root/app/src/debug/res" -o "$work/test-resources.zip"
    resources+=(-R "$work/test-resources.zip")
fi
"$tools/aapt2" link -I "$platform/android.jar" --manifest "$root/app/src/main/AndroidManifest.xml" \
  --rename-manifest-package "$application_id" --custom-package "$namespace" \
  --min-sdk-version "$min_sdk" --target-sdk-version "$target_sdk" \
  --version-code "$version_code" --version-name "$version_name" \
  --java "$work/generated" --proguard "$work/resource-keep.pro" \
  -A "$root/app/src/main/assets" -o "$work/resources.apk" "${resources[@]}"
cat > "$work/generated/com/localcopy/terminal/BuildConfig.java" <<BUILD_CONFIG
package com.localcopy.terminal;
final class BuildConfig {
    static final String VERSION_NAME = "$version_name";
    static final String RELEASE_CERT_SHA256 = "$release_cert";
}
BUILD_CONFIG
mapfile -d '' source < <(find "$root/app/src/main/java" "$work/generated" -name '*.java' -print0)
compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
if command -v javac >/dev/null 2>&1; then
  compiler=(javac)
fi
"${compiler[@]}" -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$platform/android.jar:$tools/core-lambda-stubs.jar" \
  -d "$work/classes" "${source[@]}"
(cd "$work/classes" && zip -q -r "$work/classes.jar" .)
java -cp "${COPY_R8_JAR:-$tools/lib/d8.jar}" com.android.tools.r8.R8 --release \
  --lib "$platform/android.jar" --min-api "$min_sdk" --output "$work/dex" \
  --pg-conf "$root/app/proguard-rules.pro" --pg-conf "$work/resource-keep.pro" \
  --pg-map-output "$out/$mapping" "$work/classes.jar"
zip -q -j "$work/resources.apk" "$work/dex/classes.dex"
"$tools/zipalign" -f 4 "$work/resources.apk" "$work/aligned.apk"
apk="${COPY_OUTPUT_APK:-$out/$default_apk}"
"$tools/apksigner" sign --ks "$key" --ks-key-alias app --ks-pass pass:android \
  --v4-signing-enabled false --out "$apk" "$work/aligned.apk"
"$tools/apksigner" verify --verbose "$apk"
"$tools/zipalign" -c 4 "$apk"
printf '%s\n' "$apk"
