cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})

project(nRF52810)

set(YUITAG_CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../YuiTagCore)

target_include_directories(app PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${YUITAG_CORE_DIR}/include
    ${YUITAG_CORE_DIR}/lib
)

target_sources(app PRIVATE
    src/main.c
    src/app.c
    src/yuitag_platform.c
    src/yuitag_buzzer.c
    src/yuitag_ble.c
    ${YUITAG_CORE_DIR}/src/yuitag_device.c
    ${YUITAG_CORE_DIR}/src/yuitag_crypto_common.c
    ${YUITAG_CORE_DIR}/lib/tiny_aes.c
    ${YUITAG_CORE_DIR}/lib/sha256.c
)