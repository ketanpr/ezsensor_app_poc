cmake_minimum_required(VERSION 3.22.1)
project("ezdent_sensor" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)

add_library(ezdent_sensor SHARED
    sensor_bridge.cpp
    es_descramble.cpp
    es_calibration.cpp
    es_process.cpp
    es_utils.cpp
)

target_link_libraries(ezdent_sensor
    android
    log
    jnigraphics
)
