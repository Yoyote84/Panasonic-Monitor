cmake_minimum_required(VERSION 3.22.1)
project(panasonic_monitor_native LANGUAGES C CXX)

include(FetchContent)

# Pinned libusb-cmake wrapper. Its lock file pins the bundled upstream libusb
# source revision to ed4caebb6869f4504d0c5f86abed3ba9381ac4dc
# (libusb v1.0.30-56-g...). This avoids a floating dependency.
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

add_library(panasonic_usb SHARED native_usb.cpp)
target_compile_features(panasonic_usb PRIVATE cxx_std_17)
target_compile_options(panasonic_usb PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(panasonic_usb PRIVATE libusb::usb-1.0 log)
