cmake_minimum_required(VERSION 3.22.1)
project(aster_ondevice)

find_library(log-lib    log)
find_library(android-lib android)

# ── Phase 2: Genie SDK JNI bridge ───────────────────────────────────────────
# Requires the Genie SDK to be installed:
#   Headers  → app/src/main/cpp/genie/  (GenieCommon.h, GenieDialog.h)
#   .so libs → app/src/main/jniLibs/arm64-v8a/  (libGenie.so, libQnnHtp.so, libQnnHtpV81Stub.so, libQnnHtpV81Skel.so)
#
# Obtain from: Qualcomm AI Hub / QAIRT SDK (same SDK used in ASR-LLM-TTS)

set(GENIE_HEADERS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/genie")
set(GENIE_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libGenie.so")

if(EXISTS "${GENIE_HEADERS_DIR}/GenieDialog.h" AND EXISTS "${GENIE_LIB}")
    message(STATUS "Genie SDK found — building aster_genie (Phase 2)")

    add_library(aster_genie SHARED genie_jni.cpp)

    target_include_directories(aster_genie PRIVATE ${GENIE_HEADERS_DIR})
    target_compile_options(aster_genie PRIVATE -std=c++17 -O3 -DNDEBUG -fvisibility=hidden)

    target_link_libraries(aster_genie
        ${GENIE_LIB}
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libQnnHtp.so
        ${log-lib}
        ${android-lib}
    )
else()
    message(STATUS "Genie SDK not found — skipping aster_genie (Phase 2).")
    message(STATUS "  Expected headers : ${GENIE_HEADERS_DIR}/GenieDialog.h")
    message(STATUS "  Expected library : ${GENIE_LIB}")
endif()

