#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FFMPEG_COMMIT="74e4393595cf2b5857f0da925c4e0dd74ea306e5"
FFMPEG_DIR="${ROOT}/build/ffmpeg-src"
PREFIX="${ROOT}/app/build/ffmpeg/arm64-v8a"
JNI_LIB_DIR="${ROOT}/app/build/ffmpeg/jniLibs/arm64-v8a"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "${NDK}" ]]; then
  for candidate in \
    "${ANDROID_SDK_ROOT:-}/ndk/27.2.12479018" \
    "${ANDROID_HOME:-}/ndk/27.2.12479018"; do
    if [[ -d "${candidate}" ]]; then NDK="${candidate}"; break; fi
  done
fi

if [[ -z "${NDK}" || ! -d "${NDK}/toolchains/llvm" ]]; then
  echo "Android NDK 27.2.12479018 introuvable. Définis ANDROID_NDK_HOME." >&2
  exit 1
fi

case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) HOST_TAG="linux-x86_64" ;;
  Darwin-arm64|Darwin-x86_64) HOST_TAG="darwin-x86_64" ;;
  *) echo "Hôte non pris en charge pour ce script : $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/${HOST_TAG}"
if [[ ! -d "${TOOLCHAIN}" && "$(uname -s)" == "Darwin" && -d "${NDK}/toolchains/llvm/prebuilt/darwin-arm64" ]]; then
  TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/darwin-arm64"
fi
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "Toolchain LLVM introuvable dans ${NDK}." >&2
  exit 1
fi

export PATH="${TOOLCHAIN}/bin:${PATH}"
export CC="${TOOLCHAIN}/bin/aarch64-linux-android23-clang"
export CXX="${TOOLCHAIN}/bin/aarch64-linux-android23-clang++"
export AR="${TOOLCHAIN}/bin/llvm-ar"
export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
export STRIP="${TOOLCHAIN}/bin/llvm-strip"
export NM="${TOOLCHAIN}/bin/llvm-nm"
SYSROOT="${TOOLCHAIN}/sysroot"

mkdir -p "$(dirname "${FFMPEG_DIR}")" "${PREFIX}" "${JNI_LIB_DIR}"
if [[ ! -d "${FFMPEG_DIR}/.git" ]]; then
  rm -rf "${FFMPEG_DIR}"
  git init "${FFMPEG_DIR}"
  git -C "${FFMPEG_DIR}" remote add origin https://github.com/FFmpeg/FFmpeg.git
fi
if [[ "$(git -C "${FFMPEG_DIR}" rev-parse HEAD 2>/dev/null || true)" != "${FFMPEG_COMMIT}" ]]; then
  git -C "${FFMPEG_DIR}" fetch --depth 1 origin "${FFMPEG_COMMIT}"
  git -C "${FFMPEG_DIR}" checkout --detach FETCH_HEAD
fi

cd "${FFMPEG_DIR}"
./configure \
  --prefix="${PREFIX}" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --sysroot="${SYSROOT}" \
  --cc="${CC}" \
  --cxx="${CXX}" \
  --ar="${AR}" \
  --ranlib="${RANLIB}" \
  --strip="${STRIP}" \
  --nm="${NM}" \
  --enable-pic \
  --enable-shared \
  --disable-static \
  --disable-symver \
  --enable-small \
  --enable-pthreads \
  --disable-programs \
  --disable-doc \
  --disable-network \
  --disable-autodetect \
  --disable-everything \
  --disable-gpl \
  --disable-nonfree \
  --enable-avcodec \
  --enable-avutil \
  --enable-swscale \
  --enable-decoder=dvvideo,mjpeg \
  --disable-avformat \
  --disable-avdevice \
  --disable-swresample \

JOBS="${JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)}"
make -j"${JOBS}"
make install-libs install-headers

for library in libavcodec.so libavutil.so libswscale.so; do
  if [[ ! -e "${PREFIX}/lib/${library}" ]]; then
    echo "Bibliothèque FFmpeg manquante après installation : ${PREFIX}/lib/${library}" >&2
    exit 1
  fi
  # Android packages native libraries from an ABI-named directory. Copy the
  # resolved shared object (not a symlink) so APK packaging remains portable.
  cp -L "${PREFIX}/lib/${library}" "${JNI_LIB_DIR}/${library}"
done

echo "FFmpeg ${FFMPEG_COMMIT} compilé pour arm64-v8a dans ${PREFIX}"
