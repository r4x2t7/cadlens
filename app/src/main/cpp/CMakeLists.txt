cmake_minimum_required(VERSION 3.22)
project(cadlens C)

set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# LibreDWG：只编 DWG 解码 + DXF 输出需要的那部分
#
# 源码是按文件取下来的（整包 24MB，本地网络扛不住），并且裁剪过：
#   - codepages 只留 WINDOWS-936 / 1252 / GB2312 / CP437 / MACINTOSH，
#     src/codepages.c 里缺的项已被置成 NULL（tools/trim_codepages.py）
#   - 不含 in_json.c / out_json.c / out_geojson.c；dwg_api.c 与 dynapi.c 必须留，
#     out_dxf.c 的表输出会用到里面的反射接口和对象构造
# ---------------------------------------------------------------------------
set(LW ${CMAKE_CURRENT_SOURCE_DIR}/libredwg)

add_library(libredwg STATIC
        ${LW}/src/bits.c
        ${LW}/src/classes.c
        ${LW}/src/codepages.c
        ${LW}/src/common.c
        ${LW}/src/decode.c
        ${LW}/src/decode2.c
        ${LW}/src/decode_r11.c
        ${LW}/src/decode_r2007.c
        ${LW}/src/dwg.c
        ${LW}/src/dwg_api.c
        ${LW}/src/dynapi.c
        ${LW}/src/dxfclasses.c
        ${LW}/src/encode.c
        ${LW}/src/encode2.c
        ${LW}/src/free.c
        ${LW}/src/geom.c
        ${LW}/src/hash.c
        ${LW}/src/in_dxf.c
        ${LW}/src/logging.c
        ${LW}/src/objects.c
        ${LW}/src/out_dxf.c
        ${LW}/src/print.c
        ${LW}/src/reedsolomon.c
)

target_include_directories(libredwg PUBLIC
        ${LW}/src
        ${LW}/include
        ${CMAKE_CURRENT_SOURCE_DIR}/libredwg_config
)

target_compile_options(libredwg PRIVATE
        -O2
        -fvisibility=hidden
        -Wno-unused-function
        -Wno-unused-variable
        -Wno-unused-but-set-variable
        -Wno-sign-compare
        -Wno-tautological-constant-out-of-range-compare
        -Wno-format
        # 每个函数单独成段，链接时才能把用不到的整段丢掉（LibreDWG 很大）
        -ffunction-sections
        -fdata-sections
)

# ---------------------------------------------------------------------------
# JNI 桥
# ---------------------------------------------------------------------------
add_library(cadlens_dwg SHARED dwg_bridge.c)
target_link_libraries(cadlens_dwg libredwg log)
target_compile_options(cadlens_dwg PRIVATE -O2 -Wall)
target_link_options(cadlens_dwg PRIVATE
        # 丢掉没被引用的段：只保留真正走到的那部分解码代码
        -Wl,--gc-sections
        # 发布版不留符号表，debug 留着方便看栈
        $<$<CONFIG:Release>:-Wl,--strip-all>
)
