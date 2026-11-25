cmake_minimum_required(VERSION 3.22.1)
project(panasonic_monitor_native LANGUAGES C CXX)

include(FetchContent)

# Pinned libusb-cmake wrapper. Its lock file pins the bundled upstream libusb
# source revision to ed4caebb6869f4504d0c5f86abed3ba9381ac4dc.
set(LIBUSB_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LIBUSB_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(LIBUSB_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LIBUSB_INSTALL_TARGETS OFF CACHE BOOL "" FORCE)
set(LIBUSB_ENABLE_LOGGING ON CACHE BOOL "" FORCE)
set(LIBUSB_ENABLE_DEBUG_LOGGING OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    libusb_cmake
    GIT_REPOSITORY https://github.com/libusb/libusb-cmake.git
    GIT_TAG bea6567b63796ab27c1c33257d230284fb2d8316
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(libusb_cmake)

set(FFMPEG_ROOT "" CACHE PATH "FFmpeg Android arm64-v8a prefix")
if(NOT FFMPEG_ROOT)
    message(FATAL_ERROR "FFMPEG_ROOT must point to the pinned Android FFmpeg build")
endif()

find_path(FFMPEG_INCLUDE_DIR libavcodec/avcodec.h
    PATHS "${FFMPEG_ROOT}/include" NO_DEFAULT_PATH)
find_library(FFMPEG_AVCODEC avcodec PATHS "${FFMPEG_ROOT}/lib" NO_DEFAULT_PATH)
find_library(FFMPEG_AVUTIL avutil PATHS "${FFMPEG_ROOT}/lib" NO_DEFAULT_PATH)
find_library(FFMPEG_SWSCALE swscale PATHS "${FFMPEG_ROOT}/lib" NO_DEFAULT_PATH)
if(NOT FFMPEG_INCLUDE_DIR OR NOT FFMPEG_AVCODEC OR NOT FFMPEG_AVUTIL OR NOT FFMPEG_SWSCALE)
    message(FATAL_ERROR "Pinned FFmpeg headers/libraries are missing; run scripts/build-ffmpeg-android.sh first")
endif()

add_library(ffmpeg_avcodec SHARED IMPORTED)
set_target_properties(ffmpeg_avcodec PROPERTIES IMPORTED_LOCATION "${FFMPEG_AVCODEC}")
add_library(ffmpeg_avutil SHARED IMPORTED)
set_target_properties(ffmpeg_avutil PROPERTIES IMPORTED_LOCATION "${FFMPEG_AVUTIL}")
add_library(ffmpeg_swscale SHARED IMPORTED)
set_target_properties(ffmpeg_swscale PROPERTIES IMPORTED_LOCATION "${FFMPEG_SWSCALE}")

add_library(panasonic_usb SHARED native_usb.cpp video_decoder.cpp)
target_compile_features(panasonic_usb PRIVATE cxx_std_17)
target_compile_options(panasonic_usb PRIVATE -Wall -Wextra -Werror=return-type)
target_include_directories(panasonic_usb PRIVATE "${FFMPEG_INCLUDE_DIR}")
target_link_libraries(panasonic_usb PRIVATE
    libusb::usb-1.0
    ffmpeg_avcodec
    ffmpeg_avutil
    ffmpeg_swscale
    android
    log
)
