cmake_minimum_required(VERSION 3.26)
project(nemotron_android LANGUAGES C CXX)

add_library(nemotron_android SHARED native-lib.cpp)
find_library(log-lib log)

set(NEMO_SDK_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/nemo-sdk"
    CACHE PATH "Staged NeMo-Speech.cpp SDK")

if(EXISTS "${NEMO_SDK_DIR}/lib/cmake/NeMoSpeech/NeMoSpeechConfig.cmake")
    message(STATUS "Using staged NeMoSpeech SDK: ${NEMO_SDK_DIR}")

    find_package(
        NeMoSpeech CONFIG REQUIRED COMPONENTS Diarization
        PATHS "${NEMO_SDK_DIR}/lib/cmake/NeMoSpeech"
        NO_DEFAULT_PATH
    )

    target_link_libraries(
        nemotron_android
        PRIVATE
        NeMoSpeech::Diarization
        ${log-lib}
    )
else()
    message(WARNING "NeMoSpeech Android SDK not found; JNI stub only.")
    target_link_libraries(nemotron_android PRIVATE ${log-lib})
endif()
