set(BUILD_SDL3          OFF CACHE BOOL "" FORCE)
set(TRACING_SUPPORT     OFF CACHE BOOL "" FORCE)
set(BUILD_DXVK_NATIVE   OFF CACHE BOOL "" FORCE)

# RAL: Apply Android ES3 GetData fallback patch to FNA3D if present.
# Fixes "Assertion failure at OPENGL_GetTextureData2D ... 'renderer->supports_NonES3'"
# seen with tModLoader on all ES3 renderers (native/ANGLE/MobileGlues/gl4es+angle).
# The patch is idempotent and a no-op when the submodule is absent (e.g. source
# archives) or already patched.
set(RAL_FNA3D_PATCH "${CMAKE_SOURCE_DIR}/patches/fna3d-es3-getdata.patch")
set(RAL_FNA3D_SRC "${LIBS_DIR}/FNA3D/src/FNA3D_Driver_OpenGL.c")
if(EXISTS "${RAL_FNA3D_PATCH}" AND EXISTS "${RAL_FNA3D_SRC}")
    execute_process(
        COMMAND git apply --check "${RAL_FNA3D_PATCH}"
        WORKING_DIRECTORY "${LIBS_DIR}/FNA3D"
        RESULT_VARIABLE RAL_FNA3D_PATCH_CHECK
        OUTPUT_QUIET
        ERROR_QUIET
    )
    if(RAL_FNA3D_PATCH_CHECK EQUAL 0)
        message(STATUS "RAL: applying FNA3D ES3 GetData patch")
        execute_process(
            COMMAND git apply "${RAL_FNA3D_PATCH}"
            WORKING_DIRECTORY "${LIBS_DIR}/FNA3D"
            RESULT_VARIABLE RAL_FNA3D_PATCH_RESULT
        )
        if(NOT RAL_FNA3D_PATCH_RESULT EQUAL 0)
            message(WARNING "RAL: failed to apply FNA3D ES3 patch, continuing without it")
        endif()
    else()
        # Already applied (or diverged); verify marker to avoid noisy re-apply.
        file(STRINGS "${RAL_FNA3D_SRC}" RAL_FNA3D_MARKER REGEX "OPENGL_INTERNAL_GetTextureData2D_ES3" LIMIT_COUNT 1)
        if(NOT RAL_FNA3D_MARKER)
            message(STATUS "RAL: FNA3D ES3 patch check failed, trying patch -p1 fallback")
            find_program(RAL_PATCH_EXE patch)
            if(RAL_PATCH_EXE)
                execute_process(
                    COMMAND ${RAL_PATCH_EXE} -p1 --forward -i "${RAL_FNA3D_PATCH}"
                    WORKING_DIRECTORY "${LIBS_DIR}/FNA3D"
                    RESULT_VARIABLE RAL_FNA3D_PATCH_FALLBACK
                    OUTPUT_QUIET
                    ERROR_QUIET
                )
            endif()
        endif()
    endif()
endif()

set(CMAKE_SKIP_INSTALL_RULES ON)
add_subdirectory(${LIBS_DIR}/FNA3D)
set(CMAKE_SKIP_INSTALL_RULES OFF)