# SPDX-License-Identifier: GPL-2.0-only
cmake_minimum_required(VERSION 3.22)
# This is a separate GPL-2.0 process speaking the standard OpenVPN management
# protocol. No OpenVPN 2 code is linked into the JVM or OpenVPN 3 JNI library.
include(FetchContent)
set(OPENVPN2_REVISION 7b60c2aa34992639691e3a2d651fe27301e2e9d5)
set(OPENVPN2_ANDROID_REVISION f669267e2547fddca04d0bc699739137085eb633)
set(OPENVPN2_CACHE "${CMAKE_CURRENT_SOURCE_DIR}/../build/openvpn2-sources")
file(MAKE_DIRECTORY "${OPENVPN2_CACHE}")
# Fetch only the required submodules, pinned by the Android build-support tree.
# In particular do not build or ship a second OpenVPN 3, Go stack or JNI utility.
function(fetch_openvpn2 name repository revision)
    file(LOCK "${OPENVPN2_CACHE}/${name}.lock" GUARD FUNCTION TIMEOUT 600)
    set(source "${OPENVPN2_CACHE}/${name}-${revision}")
    if(EXISTS "${source}/.git")
        execute_process(COMMAND git -C "${source}" rev-parse HEAD
            OUTPUT_VARIABLE actual OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
        if(NOT actual STREQUAL revision)
            message(FATAL_ERROR "Invalid cached OpenVPN 2 source revision: ${name}")
        endif()
    else()
        FetchContent_Declare(${name} SOURCE_DIR "${source}"
            GIT_REPOSITORY "https://github.com/${repository}.git" GIT_TAG "${revision}"
            GIT_SUBMODULES ${ARGN} GIT_SUBMODULES_RECURSE OFF)
        FetchContent_Populate(${name})
    endif()
    set(${name}_SOURCE_DIR "${source}" PARENT_SCOPE)
endfunction()
fetch_openvpn2(openvpn2 OpenVPN/openvpn ${OPENVPN2_REVISION})
fetch_openvpn2(android2 schwabe/ics-openvpn ${OPENVPN2_ANDROID_REVISION}
    main/src/main/cpp/openssl main/src/main/cpp/lz4)
set(OPENVPN2_SOURCE_DIR "${openvpn2_SOURCE_DIR}")
set(OPENVPN2_ANDROID_CPP "${android2_SOURCE_DIR}/main/src/main/cpp")
# Fail rather than accidentally using a different crypto source after cache edits.
foreach(pair "openssl;b280e0c0c81ce6f8d5291c1f8efb6712c357c28a" "lz4;5ff839680134437dbf4678f3d0c7b371d84f4964")
    list(GET pair 0 dependency)
    list(GET pair 1 revision)
    execute_process(COMMAND git -C "${OPENVPN2_ANDROID_CPP}/${dependency}" rev-parse HEAD
        OUTPUT_VARIABLE actual OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
    if(NOT actual STREQUAL revision)
        message(FATAL_ERROR "Invalid cached OpenVPN 2 dependency revision: ${dependency}")
    endif()
endforeach()

# Reuse the pinned Android crypto/assembly build recipe; resolve its relative
# paths against its own source tree without modifying downloaded sources.
include("${OPENVPN2_ANDROID_CPP}/tools.cmake")
set(SSLLIBTYPE STATIC)
set(OPENSSL_PATH "${OPENVPN2_ANDROID_CPP}/openssl")
file(READ "${OPENVPN2_ANDROID_CPP}/openssl/openssl.cmake" ssl_recipe)
string(REPLACE "\${CMAKE_CURRENT_SOURCE_DIR}" "${OPENVPN2_ANDROID_CPP}" ssl_recipe "${ssl_recipe}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/openssl.cmake" "${ssl_recipe}")
include("${CMAKE_CURRENT_BINARY_DIR}/openssl.cmake")
file(READ "${OPENVPN2_ANDROID_CPP}/lzo.cmake" lzo_recipe)
string(REPLACE "\"lzo/src\"" "\"${OPENVPN2_ANDROID_CPP}/lzo/src\"" lzo_recipe "${lzo_recipe}")
string(REPLACE "\${CMAKE_CURRENT_SOURCE_DIR}" "${OPENVPN2_ANDROID_CPP}" lzo_recipe "${lzo_recipe}")
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/lzo.cmake" "${lzo_recipe}")
include("${CMAKE_CURRENT_BINARY_DIR}/lzo.cmake")
add_library(openvpn2-lz4 STATIC "${OPENVPN2_ANDROID_CPP}/lz4/lib/lz4.c")
target_include_directories(openvpn2-lz4 PUBLIC "${OPENVPN2_ANDROID_CPP}/lz4/lib")

set(OPENVPN2_OVERLAY_DIR "${CMAKE_CURRENT_BINARY_DIR}/overlay")
file(MAKE_DIRECTORY "${OPENVPN2_OVERLAY_DIR}")
include("${CMAKE_CURRENT_SOURCE_DIR}/patch-engine.cmake")
# A validator may parse and postprocess options, but must never open management,
# transport or TUN sockets. Core 3 supplies additional profile/credential preflight.
file(READ "${OPENVPN2_SOURCE_DIR}/src/openvpn/openvpn.c" entry)
set(validation_point "            options_postprocess(&c.options, c.es);")
string(FIND "${entry}" "${validation_point}" position)
if(position EQUAL -1)
    message(FATAL_ERROR "Recheck OpenVPN 2 validation insertion against pinned source")
endif()
string(REPLACE "${validation_point}" "${validation_point}\n            if (getenv(\"NUVIO_OPENVPN2_VALIDATE_ONLY\"))\n            {\n                return 0;\n            }" entry "${entry}")
file(WRITE "${OPENVPN2_OVERLAY_DIR}/openvpn.c" "${entry}")

# Use the release's own source list. Header/resource entries are unnecessary on
# Android; source overlays above are the complete local engine modifications.
file(READ "${OPENVPN2_SOURCE_DIR}/CMakeLists.txt" upstream_build)
string(REGEX MATCH "set\\(SOURCE_FILES[^)]*\\)" source_declaration "${upstream_build}")
if(NOT source_declaration)
    message(FATAL_ERROR "Recheck pinned OpenVPN 2 source list")
endif()
string(REGEX MATCHALL "src/[A-Za-z0-9_./-]+\\.c" source_paths "${source_declaration}")
set(engine_sources "")
foreach(source IN LISTS source_paths)
    get_filename_component(source_name "${source}" NAME)
    if(source STREQUAL "src/openvpn/openvpn.c")
        list(APPEND engine_sources "${OPENVPN2_OVERLAY_DIR}/openvpn.c")
    elseif(EXISTS "${OPENVPN2_OVERLAY_DIR}/${source_name}")
        list(APPEND engine_sources "${OPENVPN2_OVERLAY_DIR}/${source_name}")
    else()
        list(APPEND engine_sources "${OPENVPN2_SOURCE_DIR}/${source}")
    endif()
endforeach()
# Keep the Android feature configuration, with release identity corrected.
file(READ "${OPENVPN2_ANDROID_CPP}/openvpn-config/config.h" android_config)
string(REPLACE "OpenVPN 2.7-icsopenvpn" "OpenVPN 2.7.9 Nuvio compatibility process" android_config "${android_config}")
string(REPLACE "\"2.7_master\"" "\"2.7.9\"" android_config "${android_config}")
file(WRITE "${OPENVPN2_OVERLAY_DIR}/config.h" "${android_config}")
add_library(nuvio-openvpn2-engine SHARED ${engine_sources})
target_include_directories(nuvio-openvpn2-engine PRIVATE "${OPENVPN2_OVERLAY_DIR}"
    "${OPENVPN2_SOURCE_DIR}/src/openvpn" "${OPENVPN2_SOURCE_DIR}/src/compat"
    "${OPENVPN2_SOURCE_DIR}/include" "${OPENVPN2_SOURCE_DIR}"
    "${OPENVPN2_ANDROID_CPP}/openvpn-config" "${OPENVPN2_ANDROID_CPP}/lzo/include")
target_compile_definitions(nuvio-openvpn2-engine PRIVATE HAVE_CONFIG_H ENABLE_CRYPTO_OPENSSL=1
    CONFIGURE_GIT_REVISION="${OPENVPN2_REVISION}" CONFIGURE_GIT_FLAGS=""
    TARGET_ABI="${ANDROID_ABI}" OPENSSL_API_COMPAT=0x11000000L
    DEFAULT_DNS_UPDOWN="/not/supported/on/android")
set_source_files_properties("${OPENVPN2_OVERLAY_DIR}/openvpn.c" PROPERTIES
    COMPILE_DEFINITIONS "main=nuvio_openvpn2_engine_main")
target_link_libraries(nuvio-openvpn2-engine PRIVATE ssl crypto lzo openvpn2-lz4)
target_compile_options(nuvio-openvpn2-engine PRIVATE -ffunction-sections -fdata-sections)
target_link_options(nuvio-openvpn2-engine PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
# A read-only nativeLibraryDir executable satisfies Android's W^X restrictions.
add_executable(nuvio-openvpn2 launcher.c)
set_target_properties(nuvio-openvpn2 PROPERTIES OUTPUT_NAME "libnuvio-openvpn2.so")
target_link_libraries(nuvio-openvpn2 PRIVATE nuvio-openvpn2-engine)
target_link_options(nuvio-openvpn2 PRIVATE -pie "-Wl,-z,max-page-size=16384")
