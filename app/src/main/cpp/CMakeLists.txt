cmake_minimum_required(VERSION 3.22.1)
project("whisper_bridge")

add_library(whisper_bridge SHARED whisper_bridge.cpp)

target_include_directories(whisper_bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(whisper_bridge
    android
    log
    dl
)
