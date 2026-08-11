# Copyright (c) 2026 Marco Morosi
#
# Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
# the European Commission - subsequent versions of the EUPL (the "Licence");
# You may not use this work except in compliance with the Licence.
# You may obtain a copy of the Licence at:
#
# https://joinup.ec.europa.eu/software/page/eupl
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the Licence is distributed on an "AS IS" basis,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the Licence for the specific language governing permissions and
# limitations under the Licence.

cmake_minimum_required(VERSION 3.21)
project(audio_engine C)

if(UNIX AND NOT APPLE)
    message(STATUS "Linux detected: building native audio engine (PulseAudio/PipeWire via dlopen).")
endif()

# Enable Objective-C ONLY on macOS
if(APPLE)
    enable_language(OBJC)
endif()

# ── Versione C standard ───────────────────────────────────────────────────────
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ── Trova JNI ─────────────────────────────────────────────────────────────────
# JAVA_HOME is passed by Gradle as a cmake variable (-DJAVA_HOME=...).
# find_package is only a fallback: the paths are built from JAVA_HOME first,
# so CMake does not pick headers from an incomplete system JRE.
if(NOT DEFINED JAVA_HOME AND DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()

if(DEFINED JAVA_HOME)
    if(WIN32)
        set(_jni_platform "win32")
    elseif(APPLE)
        set(_jni_platform "darwin")
    else()
        set(_jni_platform "linux")
    endif()
    set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include" "${JAVA_HOME}/include/${_jni_platform}")
    if(NOT EXISTS "${JAVA_HOME}/include/jni.h")
        message(FATAL_ERROR "jni.h not found in ${JAVA_HOME}/include - check JAVA_HOME")
    endif()
else()
    find_package(JNI REQUIRED)
endif()
message(STATUS "JNI include dirs: ${JNI_INCLUDE_DIRS}")

# ── Sorgenti ──────────────────────────────────────────────────────────────────
set(NATIVE_SOURCES audio_engine.c)

if(APPLE)
    # On macOS add the Objective-C file with ScreenCaptureKit
    list(APPEND NATIVE_SOURCES audio_engine_mac.m)
    set_source_files_properties(audio_engine_mac.m
        PROPERTIES COMPILE_FLAGS "-fobjc-arc"
    )
endif()

# ── Libreria condivisa ────────────────────────────────────────────────────────
add_library(audio_engine SHARED ${NATIVE_SOURCES})

target_include_directories(audio_engine PRIVATE ${JNI_INCLUDE_DIRS})

# ── Flag comuni ───────────────────────────────────────────────────────────────
target_compile_options(audio_engine PRIVATE
    $<$<C_COMPILER_ID:GNU,Clang,AppleClang>:
        -Wall -Wextra -Wno-unused-parameter -O2
    >
    $<$<C_COMPILER_ID:MSVC>:
        /W3 /O2
    >
)

# ── Per-OS linking ────────────────────────────────────────────────────────────
if(WIN32)
    target_link_libraries(audio_engine PRIVATE Ole32 Mmdevapi ksuser)

    set_target_properties(audio_engine PROPERTIES
        PREFIX ""
        OUTPUT_NAME "audio_engine"
    )

elseif(APPLE)
    # ScreenCaptureKit + AVFoundation + CoreAudio
    target_link_libraries(audio_engine PRIVATE
        "-framework ScreenCaptureKit"
        "-framework AVFoundation"
        "-framework CoreAudio"
        "-framework CoreMedia"
        "-framework CoreFoundation"
        "-framework Foundation"
        "-framework AudioToolbox"
    )
    # macOS 12.3 minimum for ScreenCaptureKit
    set_target_properties(audio_engine PROPERTIES
        MACOSX_RPATH TRUE
        BUILD_WITH_INSTALL_RPATH TRUE
        INSTALL_RPATH "@loader_path"
    )
    target_compile_options(audio_engine PRIVATE
            -mmacosx-version-min=13.0
    )

elseif(UNIX)
    # Linux: zero link-time deps on libpulse — loaded via dlopen at runtime.
    # Only pthread (for mutex) and dl (for dlopen) are required.
    find_package(Threads REQUIRED)
    target_link_libraries(audio_engine PRIVATE Threads::Threads ${CMAKE_DL_LIBS})
endif()

# ── Output directory ──────────────────────────────────────────────────────────
# The .so/.dll/.dylib goes into build/native/ and is then copied by Gradle
# into src/main/resources/native/<os>/<arch>/
set_target_properties(audio_engine PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/output"
)

message(STATUS "audio_engine target configured for ${CMAKE_SYSTEM_NAME}")