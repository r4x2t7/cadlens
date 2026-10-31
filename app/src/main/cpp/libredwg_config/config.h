/*
 * 为 Android/bionic 手写的 LibreDWG config.h。
 *
 * LibreDWG 的 configure 会生成这个文件；我们不跑 autotools，直接把
 * 需要的宏定死。取值以 NDK clang + bionic 的实际能力为准：
 *   - 有 alloca.h / ctype.h / stdint.h ...
 *   - 有 iconv.h 但实现不全，关掉，走内置码页表
 *   - wchar_t 是 4 字节，所以不定 HAVE_NATIVE_WCHAR2
 *   - HAVE_ALIGNED_ACCESS_REQUIRED 打开：让 bits.c 走 memcpy 路径，
 *     避免不同 ABI 下未对齐读取的 UB
 */
#ifndef CADLENS_LIBREDWG_CONFIG_H
#define CADLENS_LIBREDWG_CONFIG_H

#define PACKAGE_NAME "libredwg"
#define PACKAGE_TARNAME "libredwg"
#define PACKAGE_VERSION "0.13.3"
#define PACKAGE_STRING "libredwg 0.13.3"
#define PACKAGE_BUGREPORT "https://github.com/LibreDWG/libredwg/issues"
#define PACKAGE_URL "https://www.gnu.org/software/libredwg/"
#define LIBREDWG_SO_VERSION "0"

#define STDC_HEADERS 1
#define HAVE_C99 1
#define HAVE_C11 1

#define HAVE_ALIGNED_ACCESS_REQUIRED 1

/* bionic 提供的标准头 */
#define HAVE_ALLOCA_H 1
#define HAVE_CTYPE_H 1
#define HAVE_FLOAT_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIMITS_H 1
#define HAVE_MALLOC_H 0
#define HAVE_STDDEF_H 1
#define HAVE_STDINT_H 1
#define HAVE_STDIO_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_UNISTD_H 1
#define HAVE_WCHAR_H 1
#define HAVE_WCTYPE_H 1
#define HAVE_DIRENT_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_ENDIAN_H 1
#define HAVE_BYTESWAP_H 1

/* 函数可用性 */
#define HAVE_MALLOC 1
#define HAVE_REALLOC 1
#define HAVE_MEMCHR 1
#define HAVE_MEMMOVE 1
#define HAVE_STRCHR 1
#define HAVE_STRRCHR 1
#define HAVE_STRSTR 1
#define HAVE_STRCASECMP 1
#define HAVE_STRCASESTR 1
#define HAVE_STRNLEN 1
#define HAVE_STRTOL 1
#define HAVE_STRTOUL 1
#define HAVE_STRTOLL 1
#define HAVE_STRTOULL 1
#define HAVE_SQRT 1
#define HAVE_FLOOR 1
#define HAVE_SINCOS 1
#define HAVE_GETTIMEOFDAY 1
#define HAVE_GMTIME_R 1
#define HAVE_BASENAME 1
#define HAVE_LIBM 1
#define HAVE_SCANDIR 1

/* GCC/Clang 属性 */
#define HAVE_ATTRIBUTE_VISIBILITY_DEFAULT 1
#define HAVE_FUNC_ATTRIBUTE_FORMAT 1
#define HAVE_FUNC_ATTRIBUTE_GNU_FORMAT 1
#define HAVE_FUNC_ATTRIBUTE_MALLOC 1
#define HAVE_FUNC_ATTRIBUTE_NORETURN 1
#define HAVE_FUNC_ATTRIBUTE_RETURNS_NONNULL 1
#define HAVE_FUNC_ATTRIBUTE_ALIGNED 1
#define HAVE_FUNC_ATTRIBUTE_COUNTED_BY 1

#define AX_STRCASECMP_HEADER <strings.h>

/* 不启用绑定、JSON、iconv，也不需要 tracing */
#define DISABLE_BINDINGS 1
#define USE_TRACING 0
#ifndef DWG_OPTS_DEFAULT
#define DWG_OPTS_DEFAULT 0
#endif

#define SIZEOF_SIZE_T __SIZEOF_SIZE_T__
#define SIZEOF_WCHAR_T __SIZEOF_WCHAR_T__
#define PRI_SIZE_T_MODIFIER "z"
#define LT_OBJDIR ".libs/"

#endif /* CADLENS_LIBREDWG_CONFIG_H */
