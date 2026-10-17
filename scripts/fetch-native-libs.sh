#!/usr/bin/env bash
# Downloads the prebuilt sherpa-onnx Android native libraries (JNI + onnxruntime)
# that match the vendored Kotlin API, and places them in app/src/main/jniLibs.
set -euo pipefail

VERSION="${SHERPA_ONNX_VERSION:-1.13.8}"
ABIS="${ABIS:-arm64-v8a}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-v${VERSION}-android.tar.bz2"
echo "Fetching $URL"
curl -fSL --retry 4 -o "$TMP/libs.tar.bz2" "$URL"
tar xjf "$TMP/libs.tar.bz2" -C "$TMP"

for abi in $ABIS; do
  src="$(find "$TMP" -type d -name "$abi" | head -n1)"
  if [ -z "$src" ]; then
    echo "ABI $abi not found in archive" >&2
    find "$TMP" -maxdepth 4 >&2
    exit 1
  fi
  mkdir -p "$DEST/$abi"
  cp "$src"/*.so "$DEST/$abi/"
  echo "Installed into $DEST/$abi:"
  ls -la "$DEST/$abi"
done

test -f "$DEST/arm64-v8a/libsherpa-onnx-jni.so" || { echo "libsherpa-onnx-jni.so missing" >&2; exit 1; }
