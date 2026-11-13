cmake_minimum_required(VERSION 3.18)
project(runner LANGUAGES CXX)

# Download and extract the official Microsoft.Web.WebView2 NuGet SDK at configure time
# so WebView2LoaderStatic.lib is linked statically into Lindo.exe with zero external DLLs.
set(WEBVIEW2_VERSION "1.0.2903.40")
set(WEBVIEW2_SDK_DIR "${CMAKE_BINARY_DIR}/webview2_sdk")
set(WEBVIEW2_NUPKG "${CMAKE_BINARY_DIR}/Microsoft.Web.WebView2.${WEBVIEW2_VERSION}.zip")
set(WEBVIEW2_HEADER "${WEBVIEW2_SDK_DIR}/build/native/include/WebView2.h")
set(WEBVIEW2_STATIC_LIB "${WEBVIEW2_SDK_DIR}/build/native/x64/WebView2LoaderStatic.lib")

if(NOT EXISTS "${WEBVIEW2_HEADER}" OR NOT EXISTS "${WEBVIEW2_STATIC_LIB}")
  message(STATUS "Downloading Microsoft.Web.WebView2 ${WEBVIEW2_VERSION} SDK...")
  file(DOWNLOAD
    "https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/${WEBVIEW2_VERSION}"
    "${WEBVIEW2_NUPKG}"
    SHOW_PROGRESS
    TLS_VERIFY ON
    STATUS WEBVIEW2_DL_STATUS
  )
  list(GET WEBVIEW2_DL_STATUS 0 WEBVIEW2_DL_CODE)
  if(NOT WEBVIEW2_DL_CODE EQUAL 0)
    message(FATAL_ERROR "Failed to download WebView2 SDK: ${WEBVIEW2_DL_STATUS}")
  endif()
  file(MAKE_DIRECTORY "${WEBVIEW2_SDK_DIR}")
  file(ARCHIVE_EXTRACT
    INPUT "${WEBVIEW2_NUPKG}"
    DESTINATION "${WEBVIEW2_SDK_DIR}"
  )
endif()

# Define the application target. To change its name, change BINARY_NAME in the
# top-level CMakeLists.txt, not the value here, or `flutter run` will no longer
# work.
add_executable(${BINARY_NAME} WIN32
  "flutter_window.cpp"
  "main.cpp"
  "utils.cpp"
  "win32_window.cpp"
  "${FLUTTER_MANAGED_DIR}/generated_plugin_registrant.cc"
  "Runner.rc"
  "runner.exe.manifest"
)

# Apply the standard set of build settings.
apply_standard_settings(${BINARY_NAME})

# Add preprocessor definitions for the build version.
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION=\"${FLUTTER_VERSION}\"")
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION_MAJOR=${FLUTTER_VERSION_MAJOR}")
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION_MINOR=${FLUTTER_VERSION_MINOR}")
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION_PATCH=${FLUTTER_VERSION_PATCH}")
target_compile_definitions(${BINARY_NAME} PRIVATE "FLUTTER_VERSION_BUILD=${FLUTTER_VERSION_BUILD}")

# Disable Windows macros that collide with C++ standard library functions and WinHTTP.
target_compile_definitions(${BINARY_NAME} PRIVATE "NOMINMAX")
target_compile_definitions(${BINARY_NAME} PRIVATE "WIN32_LEAN_AND_MEAN")

# Add dependency libraries and include directories.
target_include_directories(${BINARY_NAME} PRIVATE
  "${CMAKE_SOURCE_DIR}"
  "${WEBVIEW2_SDK_DIR}/build/native/include"
)

target_link_libraries(${BINARY_NAME} PRIVATE
  flutter
  flutter_wrapper_app
  "${WEBVIEW2_STATIC_LIB}"
  "dwmapi.lib"
  "shlwapi.lib"
  "comdlg32.lib"
  "shell32.lib"
  "ole32.lib"
  "oleaut32.lib"
  "winhttp.lib"
  "crypt32.lib"
  "advapi32.lib"
  "version.lib"
  "user32.lib"
  "gdi32.lib"
)

# Run the Flutter tool portions of the build. This must not be removed.
add_dependencies(${BINARY_NAME} flutter_assemble)
