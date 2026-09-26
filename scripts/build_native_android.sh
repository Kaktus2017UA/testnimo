#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${ROOT}/.native-build"
ABI="${ANDROID_ABI:-arm64-v8a}"
API="${ANDROID_API:-28}"
TOOLCHAIN="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is required}/build/cmake/android.toolchain.cmake"

rm -rf "${WORK}"
mkdir -p "${WORK}/deps"

echo "==> Building for ${ABI}, Android API ${API}"

git clone --depth 1 --branch v0.2.1 \
  https://github.com/google/sentencepiece.git \
  "${WORK}/sentencepiece"

cmake -S "${WORK}/sentencepiece" -B "${WORK}/sentencepiece-build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
  -DANDROID_ABI="${ABI}" \
  -DANDROID_PLATFORM="android-${API}" \
  -DANDROID_STL=c++_shared \
  -DSPM_BUILD_TEST=OFF \
  -DSPM_ENABLE_SHARED=OFF \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="${WORK}/deps/sentencepiece"

cmake --build "${WORK}/sentencepiece-build" --target sentencepiece-static --parallel

mkdir -p "${WORK}/deps/sentencepiece/lib" "${WORK}/deps/sentencepiece/include"
cp "${WORK}/sentencepiece-build/src/libsentencepiece.a"    "${WORK}/deps/sentencepiece/lib/libsentencepiece.a"
cp "${WORK}/sentencepiece/src/sentencepiece_processor.h"    "${WORK}/deps/sentencepiece/include/"
cp "${WORK}/sentencepiece/src/sentencepiece_model.pb.h"    "${WORK}/deps/sentencepiece/include/" 2>/dev/null || true
cp "${WORK}/sentencepiece-build/src/builtin_pb/sentencepiece_model.pb.h"    "${WORK}/deps/sentencepiece/include/" 2>/dev/null || true
cp "${WORK}/sentencepiece-build/src/builtin_pb/sentencepiece.pb.h"    "${WORK}/deps/sentencepiece/include/" 2>/dev/null || true

SP_LIB="${WORK}/deps/sentencepiece/lib/libsentencepiece.a"
SP_INC="${WORK}/deps/sentencepiece/include"

test -f "${SP_LIB}" || { echo "Missing ${SP_LIB}"; exit 1; }
test -f "${SP_INC}/sentencepiece_processor.h" || { echo "Missing SentencePiece headers"; exit 1; }

git clone --recursive --depth 1 \
  https://github.com/NVIDIA/NeMo-Speech.cpp.git \
  "${WORK}/nemo-speech"

cmake -S "${WORK}/nemo-speech" -B "${WORK}/nemo-build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="${TOOLCHAIN}" \
  -DANDROID_ABI="${ABI}" \
  -DANDROID_PLATFORM="android-${API}" \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DNEMO_SPEECH_DEPENDENCY_PREFIX="${WORK}/deps" \
  -DCMAKE_PREFIX_PATH="${WORK}/deps/sentencepiece" \
  -DSENTENCEPIECE_STATIC_LIB="${SP_LIB}" \
  -DSENTENCEPIECE_INCLUDE_DIR="${SP_INC}" \
  -DCMAKE_SHARED_LINKER_FLAGS="-llog" \
  -DNEMO_SPEECH_BUILD_ASR=ON \
  -DNEMO_SPEECH_BUILD_DIAR=ON \
  -DNEMO_SPEECH_BUILD_TTS=OFF \
  -DNEMO_SPEECH_BUILD_NMT=OFF \
  -DNEMO_SPEECH_BUILD_S2S=OFF \
  -DNEMO_SPEECH_BUILD_CLI=OFF \
  -DNEMO_SPEECH_BUILD_MIC_CAPTURE=OFF \
  -DNEMO_SPEECH_BUILD_HTTP=OFF \
  -DNEMO_SPEECH_BUILD_GRPC=OFF \
  -DNEMO_SPEECH_BUILD_EXAMPLES=OFF \
  -DNEMO_SPEECH_BUILD_TESTS=OFF \
  -DNEMO_SPEECH_BUILD_TOOLS=OFF \
  -DGGML_CUDA=OFF \
  -DGGML_METAL=OFF \
  -DGGML_VULKAN=OFF \
  -DNEMO_SPEECH_GGML_PATCHED=OFF \
  -DCMAKE_INSTALL_PREFIX="${WORK}/nemo-install"

cmake --build "${WORK}/nemo-build" --parallel
cmake --install "${WORK}/nemo-build"

SDK_DST="${ROOT}/app/src/main/cpp/nemo-sdk"
JNI_DST="${ROOT}/app/src/main/jniLibs/${ABI}"

rm -rf "${SDK_DST}" "${JNI_DST}"
mkdir -p "${SDK_DST}" "${JNI_DST}"

cp -a "${WORK}/nemo-install/." "${SDK_DST}/"

find "${WORK}/nemo-install" "${WORK}/deps/sentencepiece" \
  -type f -name '*.so' -exec cp -f {} "${JNI_DST}/" \;

LIBOMP="$(find "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt" -type f -path "*/lib/clang/*/lib/linux/aarch64/libomp.so" | head -n 1 || true)"
if [[ -z "${LIBOMP}" ]]; then
  echo "libomp.so not found in Android NDK"
  exit 1
fi
cp -f "${LIBOMP}" "${JNI_DST}/libomp.so"

echo "Native SDK staged. Build with: gradle :app:assembleDebug"
