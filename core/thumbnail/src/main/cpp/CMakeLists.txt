cmake_minimum_required(VERSION 3.22.1)
project(nplayer_thumbnail LANGUAGES CXX)

add_library(nplayer_thumbnail SHARED thumbnail_retriever.cpp media_input.cpp)
target_compile_features(nplayer_thumbnail PRIVATE cxx_std_17)
target_compile_options(nplayer_thumbnail PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_include_directories(nplayer_thumbnail PRIVATE ffmpeg)
target_link_directories(nplayer_thumbnail PRIVATE "${FFMPEG_DIRECTORY}/jni/${ANDROID_ABI}")
target_link_options(nplayer_thumbnail PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(nplayer_thumbnail PRIVATE avcodec avformat avutil swscale jnigraphics log)
