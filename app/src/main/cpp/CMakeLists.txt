cmake_minimum_required(VERSION 3.26)
project(nemotron_android LANGUAGES C CXX)

add_library(nemotron_android SHARED native-lib.cpp)
find_library(log-lib log)

set(NEMO_SDK_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/nemo-sdk"
    CACHE PATH "Staged NeMo-Speech.cpp SDK")

set(NEMO_ASR_C_LIB "${NEMO_SDK_DIR}/lib/libnemo_speech_asr_c.so")

if(EXISTS "${NEMO_ASR_C_LIB}")
    message(STATUS "Using staged NeMo-Speech C ABI: ${NEMO_ASR_C_LIB}")

    add_library(nemo_speech_asr_c SHARED IMPORTED)
    set_target_properties(nemo_speech_asr_c PROPERTIES
        IMPORTED_LOCATION "${NEMO_ASR_C_LIB}"
        INTERFACE_INCLUDE_DIRECTORIES "${NEMO_SDK_DIR}/include"
    )

    target_include_directories(
        nemotron_android
        PRIVATE
        "${NEMO_SDK_DIR}/include"
    )

    target_link_libraries(
        nemotron_android
        PRIVATE
        nemo_speech_asr_c
        ${log-lib}
    )
else()
    message(WARNING "NeMo-Speech Android SDK not found; JNI stub only.")
    target_link_libraries(nemotron_android PRIVATE ${log-lib})
endif()
