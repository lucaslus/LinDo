#ifndef RUNNER_FLUTTER_WINDOW_H_
#define RUNNER_FLUTTER_WINDOW_H_

#include <flutter/dart_project.h>
#include <flutter/encodable_value.h>
#include <flutter/flutter_view_controller.h>
#include <flutter/method_channel.h>

#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "win32_window.h"
#include <shellapi.h>

class FlutterWindow : public Win32Window {
 public:
  explicit FlutterWindow(const flutter::DartProject& project,
                         const std::string& initial_deep_link = "");
  virtual ~FlutterWindow();

  struct WebBridgeState;

 protected:
  // Win32Window:
  bool OnCreate() override;
  void OnDestroy() override;
  LRESULT MessageHandler(HWND window, UINT const message, WPARAM const wparam,
                         LPARAM const lparam) noexcept override;

 private:
  void SetupChannels();
  void HandlePlatformCall(
      const flutter::MethodCall<flutter::EncodableValue>& call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
  void HandleBrowserCall(
      const flutter::MethodCall<flutter::EncodableValue>& call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  void InitTrayIcon();
  void UpdateTrayTooltip();
  void RemoveTrayIcon();
  void ShowTrayContextMenu();
  void RestoreMainWindow();
  void ApplyWindowDarkMode(bool is_dark);
  void PostUiTask(std::function<void()> task);
  void DrainUiTasks();

  std::wstring EnsureStateDirWide() const;

  flutter::DartProject project_;
  std::string pending_deep_link_;
  std::unique_ptr<flutter::FlutterViewController> flutter_controller_;

  std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>>
      platform_channel_;
  std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>>
      browser_channel_;

  NOTIFYICONDATAW tray_nid_{};
  bool tray_added_ = false;
  bool is_quitting_ = false;
  bool is_dark_mode_ = false;
  int unread_count_ = 0;
  std::wstring tray_username_;

  std::mutex ui_task_mutex_;
  std::vector<std::function<void()>> ui_tasks_;

  std::unique_ptr<WebBridgeState> web_state_;
};

#endif  // RUNNER_FLUTTER_WINDOW_H_
