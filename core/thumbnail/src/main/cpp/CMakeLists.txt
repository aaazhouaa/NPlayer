cmake_minimum_required(VERSION 3.22.1)
project(nextplayer_thumbnail LANGUAGES CXX)

add_library(nextplayer_thumbnail SHARED thumbnail_retriever.cpp media_input.cpp)
target_compile_features(nextplayer_thumbnail PRIVATE cxx_std_17)
target_compile_options(nextplayer_thumbnail PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_include_directories(nextplayer_thumbnail PRIVATE ffmpeg)
target_link_directories(nextplayer_thumbnail PRIVATE "${FFMPEG_DIRECTORY}/jni/${ANDROID_ABI}")
target_link_options(nextplayer_thumbnail PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(nextplayer_thumbnail PRIVATE avcodec avformat avutil swscale jnigraphics log)
