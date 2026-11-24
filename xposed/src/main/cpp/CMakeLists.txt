cmake_minimum_required(VERSION 3.22.1)

project("Awalocation")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(dobby REQUIRED CONFIG)

add_library(awalocation SHARED
        main.cpp
        sensor_simulator.cpp
        sensor_hook.cpp)

target_link_libraries(awalocation android log dobby::dobby)
target_link_options(awalocation PRIVATE "-Wl,-z,max-page-size=16384")
