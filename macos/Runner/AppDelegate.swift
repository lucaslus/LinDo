import Cocoa
import FlutterMacOS

@main
class AppDelegate: FlutterAppDelegate {
  static weak var shared: AppDelegate?

  var isQuitting: Bool = false
  weak var mainWindow: MainFlutterWindow?
  var platformChannel: FlutterMethodChannel?

  private var pendingDeepLinks: [String] = []
  private var statusItem: NSStatusItem?
  private var unreadMenuItem: NSMenuItem?
  private var currentUnreadCount: Int = 0
  private var currentUsername: String = ""
  private var cachedTrayImage: NSImage?

  override init() {
    super.init()
    AppDelegate.shared = self
  }

  override func applicationDidFinishLaunching(_ notification: Notification) {
    AppDelegate.shared = self
    if let mainMenu = NSApp.mainMenu {
      replaceMenuPlaceholders(in: mainMenu, appName: "Lindo")
    }
    updateTrayState(unreadCount: currentUnreadCount, username: currentUsername)
    super.applicationDidFinishLaunching(notification)
  }

  override func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
    return false
  }

  override func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
    showMainWindow()
    return true
  }

  override func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
    isQuitting = true
    mainWindow?.prepareForTermination()
    return .terminateNow
  }

  override func applicationSupportsSecureRestorableState(_ app: NSApplication) -> Bool {
    return true
  }

  override func application(_ application: NSApplication, open urls: [URL]) {
    for url in urls {
      let uri = url.absoluteString
      if !uri.isEmpty {
        dispatchAuthDeepLink(uri)
      }
    }
  }

  func attachPlatformChannel(_ channel: FlutterMethodChannel, window: MainFlutterWindow) {
    self.platformChannel = channel
    self.mainWindow = window
    if !pendingDeepLinks.isEmpty {
      let queued = pendingDeepLinks
      pendingDeepLinks.removeAll()
      for uri in queued {
        channel.invokeMethod("onAuthDeepLink", arguments: ["uri": uri])
      }
    }
  }

  func dispatchAuthDeepLink(_ uri: String) {
    showMainWindow()
    if let channel = platformChannel {
      channel.invokeMethod("onAuthDeepLink", arguments: ["uri": uri])
    } else {
      pendingDeepLinks.append(uri)
    }
  }

  func showMainWindow() {
    DispatchQueue.main.async { [weak self] in
      NSApp.activate(ignoringOtherApps: true)
      if let win = self?.mainWindow ?? (NSApp.windows.first { $0 is MainFlutterWindow } as? MainFlutterWindow) {
        if win.isMiniaturized {
          win.deminiaturize(nil)
        }
        win.makeKeyAndOrderFront(nil)
      }
    }
  }

  func updateTrayState(unreadCount: Int, username: String) {
    DispatchQueue.main.async { [weak self] in
      guard let self = self else { return }
      self.currentUnreadCount = max(0, unreadCount)
      self.currentUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)

      if self.statusItem == nil {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        let menu = NSMenu(title: "Lindo")

        let openItem = NSMenuItem(
          title: "打开 Lindo 主窗口",
          action: #selector(self.handleTrayOpenMainWindow),
          keyEquivalent: ""
        )
        openItem.target = self
        menu.addItem(openItem)

        let msgItem = NSMenuItem(
          title: "未读消息",
          action: #selector(self.handleTrayOpenMessages),
          keyEquivalent: ""
        )
        msgItem.target = self
        menu.addItem(msgItem)
        self.unreadMenuItem = msgItem

        let pubItem = NSMenuItem(
          title: "发布新话题",
          action: #selector(self.handleTrayOpenPublish),
          keyEquivalent: ""
        )
        pubItem.target = self
        menu.addItem(pubItem)

        menu.addItem(NSMenuItem.separator())

        let quitItem = NSMenuItem(
          title: "退出 Lindo",
          action: #selector(self.handleTrayQuit),
          keyEquivalent: "q"
        )
        quitItem.target = self
        menu.addItem(quitItem)

        item.menu = menu
        self.statusItem = item
      }

      if let button = self.statusItem?.button {
        button.image = self.resolveTrayImage()
        if self.currentUnreadCount > 0 {
          button.imagePosition = .imageLeading
          button.title = " \(self.currentUnreadCount)"
        } else {
          button.imagePosition = .imageOnly
          button.title = ""
        }
        var tip = "Lindo"
        if !self.currentUsername.isEmpty {
          tip += " (@\(self.currentUsername))"
        }
        if self.currentUnreadCount > 0 {
          tip += " - \(self.currentUnreadCount) 条未读消息"
        }
        button.toolTip = tip
      }

      if let msgItem = self.unreadMenuItem {
        msgItem.title = self.currentUnreadCount > 0
          ? "未读消息 (\(self.currentUnreadCount))"
          : "未读消息"
      }
    }
  }

  private func resolveTrayImage() -> NSImage {
    if let cached = cachedTrayImage {
      return cached
    }
    let assetRelKey = FlutterDartProject.lookupKey(forAsset: "assets/desktop/tray_mark.png")
    let candidates: [URL] = [
      Bundle.main.bundleURL
        .appendingPathComponent("Contents/Frameworks/App.framework/Resources")
        .appendingPathComponent(assetRelKey),
      Bundle.main.resourceURL?.appendingPathComponent(assetRelKey),
      URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        .appendingPathComponent("assets/desktop/tray_mark.png"),
    ].compactMap { $0 }

    for url in candidates {
      if FileManager.default.fileExists(atPath: url.path),
         let raw = NSImage(contentsOf: url) {
        let sized = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { rect in
          NSGraphicsContext.current?.imageInterpolation = .high
          raw.draw(
            in: rect,
            from: NSRect(origin: .zero, size: raw.size),
            operation: .sourceOver,
            fraction: 1.0
          )
          return true
        }
        sized.isTemplate = false
        cachedTrayImage = sized
        return sized
      }
    }

    let fallback = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { rect in
      let inset = rect.insetBy(dx: 1.5, dy: 1.5)
      let path = NSBezierPath(roundedRect: inset, xRadius: 4.5, yRadius: 4.5)
      path.lineWidth = 1.6
      NSColor.labelColor.setStroke()
      path.stroke()

      let leftEye = NSBezierPath(ovalIn: NSRect(x: 5.5, y: 9.2, width: 2.0, height: 2.4))
      let rightEye = NSBezierPath(ovalIn: NSRect(x: 10.5, y: 9.2, width: 2.0, height: 2.4))
      NSColor.labelColor.setFill()
      leftEye.fill()
      rightEye.fill()

      let smile = NSBezierPath()
      smile.lineWidth = 1.4
      smile.lineCapStyle = .round
      smile.move(to: NSPoint(x: 6.4, y: 6.4))
      smile.curve(
        to: NSPoint(x: 11.6, y: 6.4),
        controlPoint1: NSPoint(x: 7.8, y: 4.8),
        controlPoint2: NSPoint(x: 10.2, y: 4.8)
      )
      smile.stroke()
      return true
    }
    fallback.isTemplate = true
    cachedTrayImage = fallback
    return fallback
  }

  @objc private func handleTrayOpenMainWindow() {
    showMainWindow()
  }

  @objc private func handleTrayOpenMessages() {
    showMainWindow()
    platformChannel?.invokeMethod("onTrayCommand", arguments: ["command": "open_messages"])
  }

  @objc private func handleTrayOpenPublish() {
    showMainWindow()
    platformChannel?.invokeMethod("onTrayCommand", arguments: ["command": "open_publish"])
  }

  @objc private func handleTrayQuit() {
    isQuitting = true
    NSApp.terminate(nil)
  }

  private func replaceMenuPlaceholders(in menu: NSMenu, appName: String) {
    if menu.title.contains("APP_NAME") {
      menu.title = menu.title.replacingOccurrences(of: "APP_NAME", with: appName)
    }
    for item in menu.items {
      if item.title.contains("APP_NAME") {
        item.title = item.title.replacingOccurrences(of: "APP_NAME", with: appName)
      }
      if let sub = item.submenu {
        replaceMenuPlaceholders(in: sub, appName: appName)
      }
    }
  }
}
