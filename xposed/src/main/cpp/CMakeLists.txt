cmake_minimum_required(VERSION 3.22.1)

project("Portal")

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(dobby REQUIRED CONFIG)

add_library(portal SHARED
        main.cpp
        sensor_simulator.cpp
        sensor_hook.cpp)

target_link_libraries(portal android log dobby::dobby)
