# Biblioteca nativa do app: espeak-ng 1.52.0 (só a conversão texto → fonemas) + ponte JNI.
# O código do espeak-ng é baixado pelo GitHub Actions em ./espeak-ng (ver .github/workflows/android.yml).
cmake_minimum_required(VERSION 3.18)
project(leitor_espeak VERSION 1.52.0 LANGUAGES C CXX)

set(ESPEAK_SRC ${CMAKE_CURRENT_SOURCE_DIR}/espeak-ng)
if(NOT EXISTS ${ESPEAK_SRC}/src/libespeak-ng/espeak_api.c)
  message(FATAL_ERROR "Código do espeak-ng 1.52.0 não encontrado em ${ESPEAK_SRC}. Rode: git clone --depth 1 -b 1.52.0 https://github.com/espeak-ng/espeak-ng ${ESPEAK_SRC}")
endif()

# Só a análise de texto: sem áudio, MBROLA, sonic, Klatt, speech-player ou threads
set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
set(USE_KLATT OFF CACHE BOOL "" FORCE)
set(USE_SPEECHPLAYER OFF CACHE BOOL "" FORCE)
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
include(CheckSymbolExists)
check_symbol_exists(mkstemp "stdlib.h" HAVE_MKSTEMP)

add_subdirectory(${ESPEAK_SRC}/src espeak-ng-build EXCLUDE_FROM_ALL)

add_library(espeak_jni SHARED espeak_jni.c)
target_link_libraries(espeak_jni PRIVATE espeak-ng log m)
