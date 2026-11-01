cmake_minimum_required(VERSION 3.18.1)
project(suixing_terminal LANGUAGES CXX)

add_library(suixing_terminal SHARED terminal_pty.cpp)
target_compile_features(suixing_terminal PRIVATE cxx_std_17)
target_compile_options(suixing_terminal PRIVATE -Wall -Wextra -Werror)
