cmake_minimum_required(VERSION 3.22.1)
project(librehu_hw C)

add_library(librehu_hw SHARED librehu_hw.c)
target_compile_options(librehu_hw PRIVATE -Wall -Wextra -Werror)
target_link_libraries(librehu_hw log)
