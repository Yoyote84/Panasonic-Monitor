#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "${BUILD_DIR}"' EXIT

CXX="${CXX:-c++}"
"${CXX}" -std=c++17 -Wall -Wextra -Werror -pedantic \
  -I"${ROOT}/app/src/main/cpp" \
  "${ROOT}/tests/cpp/uvc_frame_assembler_test.cpp" \
  -o "${BUILD_DIR}/uvc_frame_assembler_test"

"${BUILD_DIR}/uvc_frame_assembler_test"
