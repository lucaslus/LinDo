#include <flutter/dart_project.h>
#include <flutter/flutter_view_controller.h>
#include <windows.h>

#include <algorithm>

#include "flutter_window.h"
#include "utils.h"

int APIENTRY wWinMain(_In_ HINSTANCE instance, _In_opt_ HINSTANCE prev,
                      _In_ wchar_t *command_line, _In_ int show_command) {
  // Attach to console when present (e.g., 'flutter run') or create a
  // new console when running with a debugger.
  if (!::AttachConsole(ATTACH_PARENT_PROCESS) && ::IsDebuggerPresent()) {
    CreateAndAttachConsole();
  }

  // Initialize COM on the UI STA thread for Flutter plugins and WebView2.
  ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  flutter::DartProject project(L"data");

  std::vector<std::string> command_line_arguments =
      GetCommandLineArguments();

  std::string initial_deep_link;
  for (const auto& arg : command_line_arguments) {
    if (arg.rfind("lindo://", 0) == 0 || arg.rfind("discourse://", 0) == 0) {
      initial_deep_link = arg;
      break;
    }
  }

  project.set_dart_entrypoint_arguments(std::move(command_line_arguments));

  constexpr unsigned int kDefaultWidth = 1180;
  constexpr unsigned int kDefaultHeight = 780;

  RECT work_area = {0, 0, 1920, 1080};
  ::SystemParametersInfoW(SPI_GETWORKAREA, 0, &work_area, 0);
  HMONITOR primary_mon = ::MonitorFromPoint({0, 0}, MONITOR_DEFAULTTOPRIMARY);
  UINT dpi = FlutterDesktopGetDpiForMonitor(primary_mon);
  double scale = dpi > 0 ? (static_cast<double>(dpi) / 96.0) : 1.0;

  int work_w_logical = static_cast<int>((work_area.right - work_area.left) / scale);
  int work_h_logical = static_cast<int>((work_area.bottom - work_area.top) / scale);
  unsigned int start_x = static_cast<unsigned int>(
      (std::max)(20, (work_w_logical - static_cast<int>(kDefaultWidth)) / 2));
  unsigned int start_y = static_cast<unsigned int>(
      (std::max)(20, (work_h_logical - static_cast<int>(kDefaultHeight)) / 2));

  FlutterWindow window(project, initial_deep_link);
  Win32Window::Point origin(start_x, start_y);
  Win32Window::Size size(kDefaultWidth, kDefaultHeight);
  if (!window.Create(L"Lindo", origin, size)) {
    ::CoUninitialize();
    return EXIT_FAILURE;
  }
  window.SetQuitOnClose(true);

  ::MSG msg;
  while (::GetMessage(&msg, nullptr, 0, 0)) {
    ::TranslateMessage(&msg);
    ::DispatchMessage(&msg);
  }

  ::CoUninitialize();
  return EXIT_SUCCESS;
}
