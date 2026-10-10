#!/bin/sh
# Builds a debug-signed APK without Gradle or the Android SDK manager, for environments
# that can't reach Google's Maven or SDK hosts. Normal builds should use ./gradlew.
#
# Needs: javac (JDK 17+), and from Ubuntu apt: aapt aidl dalvik-exchange apksigner zipalign.
# ANDROID_JAR: an android.jar for API 29+ to compile against, e.g. Robolectric's
#   org.robolectric:android-all from Maven Central.
# FRAMEWORK_RES: framework resources for aapt (default: Ubuntu's android-framework-res package).
# KEYSTORE: a keystore with alias "androiddebugkey" (password "android"); created if missing.
set -eu

HERE=$(cd "$(dirname "$0")/.." && pwd)
MAIN="$HERE/app/src/main"
OUT=${OUT:-"$HERE/app/build/sandbox"}
: "${ANDROID_JAR:?set ANDROID_JAR}"
FRAMEWORK_RES=${FRAMEWORK_RES:-/usr/share/android-framework-res/framework-res.apk}
KEYSTORE=${KEYSTORE:-"$OUT/debug.keystore"}

rm -rf "$OUT/gen" "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# Stand-in for the SDK's framework.aidl: the only framework type the AIDL files use.
echo "parcelable android.os.Bundle;" > "$OUT/framework.aidl"
for f in "$MAIN"/aidl/io/rebble/pebblekit2/common/*.aidl; do
    aidl -I"$MAIN/aidl" -p"$OUT/framework.aidl" -o"$OUT/gen" "$f"
done

# aapt needs the package in the manifest; Gradle supplies it from `namespace`.
sed 's|<manifest |<manifest package="io.github.staindk.nacashu" |' \
    "$MAIN/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
aapt package -f -m --auto-add-overlay \
    -M "$OUT/AndroidManifest.xml" -S "$MAIN/res" -I "$FRAMEWORK_RES" \
    -J "$OUT/gen" --min-sdk-version 29 --target-sdk-version 36 \
    --version-code 1 --version-name 0.1.0 \
    -F "$OUT/unaligned.apk"

javac --release 8 -nowarn -cp "$ANDROID_JAR" -d "$OUT/classes" \
    $(find "$MAIN/java" "$OUT/gen" -name '*.java')

dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/dex/classes.dex" "$OUT/classes"
(cd "$OUT/dex" && aapt add "$OUT/unaligned.apk" classes.dex >/dev/null)

zipalign -f 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
        -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
        -keyalg RSA -keysize 2048 -validity 10000 >/dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias androiddebugkey \
    --out "$OUT/nacashu.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/nacashu.apk"
echo "$OUT/nacashu.apk"
