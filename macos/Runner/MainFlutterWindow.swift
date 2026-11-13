import Cocoa
import FlutterMacOS
import UniformTypeIdentifiers
import WebKit

private final class LinDoModalPanelWindow: NSWindow {
  override var canBecomeKey: Bool { true }
  override var canBecomeMain: Bool { false }
}

final class MainFlutterWindow: NSWindow, NSWindowDelegate {
  private var platformChannel: FlutterMethodChannel?
  private var browserChannel: FlutterMethodChannel?
  private var browserCoordinator: LinDoMacBrowserCoordinator?
  private var enterFullScreenObserver: NSObjectProtocol?
  private var exitFullScreenObserver: NSObjectProtocol?

  override func awakeFromNib() {
    let flutterViewController = FlutterViewController()
    self.contentViewController = flutterViewController

    self.title = "Lindo"
    self.titleVisibility = .hidden
    self.titlebarAppearsTransparent = true
    self.styleMask.insert(.fullSizeContentView)
    self.isMovableByWindowBackground = false
    self.minSize = NSSize(width: 420, height: 620)
    self.setContentSize(NSSize(width: 1180, height: 780))
    self.center()
    self.delegate = self

    RegisterGeneratedPlugins(registry: flutterViewController)

    let messenger = flutterViewController.engine.binaryMessenger
    let platChannel = FlutterMethodChannel(
      name: "do_.linux.lindo/platform",
      binaryMessenger: messenger
    )
    let webChannel = FlutterMethodChannel(
      name: "do_.linux.lindo/browser",
      binaryMessenger: messenger
    )

    self.platformChannel = platChannel
    self.browserChannel = webChannel

    let coordinator = LinDoMacBrowserCoordinator(
      parentWindow: self,
      browserChannel: webChannel,
      platformChannel: platChannel
    )
    self.browserCoordinator = coordinator

    platChannel.setMethodCallHandler { [weak self] call, result in
      self?.handlePlatformCall(call, result: result)
    }
    webChannel.setMethodCallHandler { [weak coordinator] call, result in
      guard let coordinator = coordinator else {
        result(FlutterError(code: "DISPOSED", message: "Browser coordinator disposed", details: nil))
        return
      }
      coordinator.handleMethodCall(call, result: result)
    }

    AppDelegate.shared?.attachPlatformChannel(platChannel, window: self)

    enterFullScreenObserver = NotificationCenter.default.addObserver(
      forName: NSWindow.didEnterFullScreenNotification,
      object: self,
      queue: .main
    ) { [weak self] _ in
      self?.platformChannel?.invokeMethod("onMacTitleBarInset", arguments: ["inset": 0.0])
      self?.browserCoordinator?.syncModalFrame()
    }

    exitFullScreenObserver = NotificationCenter.default.addObserver(
      forName: NSWindow.didExitFullScreenNotification,
      object: self,
      queue: .main
    ) { [weak self] _ in
      self?.platformChannel?.invokeMethod("onMacTitleBarInset", arguments: ["inset": 28.0])
      self?.browserCoordinator?.syncModalFrame()
    }

    super.awakeFromNib()
  }

  deinit {
    if let obs = enterFullScreenObserver {
      NotificationCenter.default.removeObserver(obs)
    }
    if let obs = exitFullScreenObserver {
      NotificationCenter.default.removeObserver(obs)
    }
  }

  func prepareForTermination() {
    browserCoordinator?.closeModal(notifyDart: false)
  }

  func windowShouldClose(_ sender: NSWindow) -> Bool {
    if AppDelegate.shared?.isQuitting == true {
      browserCoordinator?.closeModal(notifyDart: false)
      return true
    }
    browserCoordinator?.closeModal(notifyDart: true)
    self.orderOut(nil)
    return false
  }

  private func handlePlatformCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let args = call.arguments as? [String: Any] ?? [:]

    switch call.method {
    case "getStateDirectory":
      let stateDirPath = (NSHomeDirectory() as NSString).appendingPathComponent(".lindo_flutter")
      try? FileManager.default.createDirectory(
        atPath: stateDirPath,
        withIntermediateDirectories: true,
        attributes: [.posixPermissions: 0o700]
      )
      let inset = self.styleMask.contains(.fullScreen) ? 0.0 : 28.0
      platformChannel?.invokeMethod("onMacTitleBarInset", arguments: ["inset": inset])
      result(stateDirPath)

    case "syncWindowTheme":
      let isDark = (args["isDark"] as? Bool) ?? false
      self.appearance = NSAppearance(named: isDark ? .darkAqua : .aqua)
      browserCoordinator?.syncModalAppearance(isDark: isDark)
      result(nil)

    case "updateTrayState":
      let unreadCount = (args["unreadCount"] as? NSNumber)?.intValue ?? 0
      let username = (args["username"] as? String) ?? ""
      AppDelegate.shared?.updateTrayState(unreadCount: unreadCount, username: username)
      result(nil)

    case "applyLauncherIcon":
      result(true)

    case "saveDiagnosticFile":
      let suggestedName = (args["suggestedName"] as? String) ?? "lindo-diagnostics.log"
      let content = (args["content"] as? String) ?? ""
      let panel = NSSavePanel()
      panel.canCreateDirectories = true
      panel.nameFieldStringValue = suggestedName
      panel.isExtensionHidden = false

      let completion: (NSApplication.ModalResponse) -> Void = { response in
        guard response == .OK, let targetUrl = panel.url else {
          result(nil)
          return
        }
        do {
          try content.write(to: targetUrl, atomically: true, encoding: .utf8)
          result(targetUrl.path)
        } catch {
          result(nil)
        }
      }

      NSApp.activate(ignoringOtherApps: true)
      if self.isVisible {
        panel.beginSheetModal(for: self, completionHandler: completion)
      } else {
        panel.begin(completionHandler: completion)
      }

    case "pickAttachmentFile":
      let panel = NSOpenPanel()
      panel.canChooseFiles = true
      panel.canChooseDirectories = false
      panel.allowsMultipleSelection = false

      let completion: (NSApplication.ModalResponse) -> Void = { response in
        guard response == .OK, let selectedUrl = panel.url else {
          result(nil)
          return
        }
        do {
          let data = try Data(contentsOf: selectedUrl)
          let mimeType = Self.detectMimeType(for: selectedUrl)
          result([
            "name": selectedUrl.lastPathComponent,
            "mimeType": mimeType,
            "bytes": FlutterStandardTypedData(bytes: data),
            "path": selectedUrl.path,
          ])
        } catch {
          result(nil)
        }
      }

      NSApp.activate(ignoringOtherApps: true)
      if self.isVisible {
        panel.beginSheetModal(for: self, completionHandler: completion)
      } else {
        panel.begin(completionHandler: completion)
      }

    case "openExternalUrl":
      guard let rawUrl = args["url"] as? String,
            let targetUrl = URL(string: rawUrl) else {
        result(false)
        return
      }
      result(NSWorkspace.shared.open(targetUrl))

    default:
      result(FlutterMethodNotImplemented)
    }
  }

  private static func detectMimeType(for url: URL) -> String {
    let ext = url.pathExtension.lowercased()
    if !ext.isEmpty,
       let utType = UTType(filenameExtension: ext),
       let mime = utType.preferredMIMEType {
      return mime
    }
    switch ext {
    case "png": return "image/png"
    case "jpg", "jpeg": return "image/jpeg"
    case "gif": return "image/gif"
    case "webp": return "image/webp"
    case "svg": return "image/svg+xml"
    case "pdf": return "application/pdf"
    case "txt", "log", "md": return "text/plain"
    case "json": return "application/json"
    case "zip": return "application/zip"
    case "mp4": return "video/mp4"
    default: return "application/octet-stream"
    }
  }
}

final class LinDoMacBrowserCoordinator: NSObject, WKNavigationDelegate, WKUIDelegate, WKHTTPCookieStoreObserver {
  private weak var parentWindow: MainFlutterWindow?
  private let browserChannel: FlutterMethodChannel
  private let platformChannel: FlutterMethodChannel

  private let sharedProcessPool = WKProcessPool()
  private var hiddenHostWindow: NSWindow?
  private var apiWebView: WKWebView?
  private var apiOriginReady: Bool = false
  private var cachedUserAgent: String = ""
  private var pendingReadyCallbacks: [() -> Void] = []

  private var modalWindow: LinDoModalPanelWindow?
  private var modalClipView: NSView?
  private var modalWebView: WKWebView?
  private var modalMode: String = "login"
  private var modalNormRect: NSRect = NSRect(x: 0.1, y: 0.1, width: 0.8, height: 0.8)
  private var modalIsDark: Bool = false
  private var modalCompleted: Bool = false
  private var modalProbeInFlight: Bool = false
  private var modalGeneration: UInt64 = 0

  private var moveObserver: NSObjectProtocol?
  private var resizeObserver: NSObjectProtocol?
  private var progressObservation: NSKeyValueObservation?
  private var titleObservation: NSKeyValueObservation?
  private var urlObservation: NSKeyValueObservation?
  private var modalPollTimer: Timer?
  private var observingCookieStore: Bool = false

  init(
    parentWindow: MainFlutterWindow,
    browserChannel: FlutterMethodChannel,
    platformChannel: FlutterMethodChannel
  ) {
    self.parentWindow = parentWindow
    self.browserChannel = browserChannel
    self.platformChannel = platformChannel
    super.init()
  }

  deinit {
    closeModal(notifyDart: false)
  }

  func handleMethodCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let args = call.arguments as? [String: Any] ?? [:]

    switch call.method {
    case "initialize":
      ensureApiWebViewReady { [weak self] in
        guard let self = self, let webView = self.apiWebView else {
          result(["ok": false, "userAgent": ""])
          return
        }
        if !self.cachedUserAgent.isEmpty {
          result(["ok": true, "userAgent": self.cachedUserAgent])
          return
        }
        webView.evaluateJavaScript("navigator.userAgent") { [weak self] val, _ in
          let ua = (val as? String) ?? ""
          self?.cachedUserAgent = ua
          result(["ok": true, "userAgent": ua])
        }
      }

    case "executeFetch":
      let method = ((args["method"] as? String) ?? "GET").uppercased()
      let rawUrl = (args["url"] as? String) ?? ""
      let headers = Self.stringMap(from: args["headers"])
      let body = args["body"] as? String
      executeFetch(method: method, rawUrl: rawUrl, headers: headers, body: body, result: result)

    case "uploadMultipart":
      let rawUrl = (args["url"] as? String) ?? ""
      let headers = Self.stringMap(from: args["headers"])
      let fields = Self.stringMap(from: args["fields"])
      let fileField = (args["fileField"] as? String) ?? "files[]"
      let fileName = (args["fileName"] as? String) ?? "upload.bin"
      let mimeType = (args["mimeType"] as? String) ?? "application/octet-stream"
      let base64Data = (args["base64Data"] as? String) ?? ""
      uploadMultipart(
        rawUrl: rawUrl,
        headers: headers,
        fields: fields,
        fileField: fileField,
        fileName: fileName,
        mimeType: mimeType,
        base64Data: base64Data,
        result: result
      )

    case "fetchBinary":
      let rawUrl = (args["url"] as? String) ?? ""
      fetchBinary(rawUrl: rawUrl, result: result)

    case "getCookies":
      let rawUrl = (args["url"] as? String) ?? "https://linux.do/"
      collectCookieHeader(for: rawUrl) { joined in
        result(joined)
      }

    case "setCookies":
      let rawUrl = (args["url"] as? String) ?? "https://linux.do/"
      let rawCookies = (args["cookies"] as? String) ?? ""
      applyRawCookies(rawCookies, urlString: rawUrl) {
        result(nil)
      }

    case "clearSession":
      clearSession {
        result(nil)
      }

    case "openModal":
      let url = (args["url"] as? String) ?? "https://linux.do/login"
      let mode = (args["mode"] as? String) ?? "login"
      let left = (args["left"] as? NSNumber)?.doubleValue ?? 0.1
      let top = (args["top"] as? NSNumber)?.doubleValue ?? 0.1
      let width = (args["width"] as? NSNumber)?.doubleValue ?? 0.8
      let height = (args["height"] as? NSNumber)?.doubleValue ?? 0.8
      let isDark = (args["isDark"] as? Bool) ?? false
      openModal(
        url: url,
        mode: mode,
        normRect: NSRect(x: left, y: top, width: width, height: height),
        isDark: isDark
      )
      result(nil)

    case "updateModalRect":
      let left = (args["left"] as? NSNumber)?.doubleValue ?? Double(modalNormRect.origin.x)
      let top = (args["top"] as? NSNumber)?.doubleValue ?? Double(modalNormRect.origin.y)
      let width = (args["width"] as? NSNumber)?.doubleValue ?? Double(modalNormRect.size.width)
      let height = (args["height"] as? NSNumber)?.doubleValue ?? Double(modalNormRect.size.height)
      modalNormRect = NSRect(x: left, y: top, width: max(0.01, width), height: max(0.01, height))
      syncModalFrame()
      result(nil)

    case "reloadModal":
      modalWebView?.reload()
      result(nil)

    case "goBackModal":
      if let wv = modalWebView, wv.canGoBack {
        wv.goBack()
      }
      result(nil)

    case "closeModal":
      closeModal(notifyDart: true)
      result(nil)

    default:
      result(FlutterMethodNotImplemented)
    }
  }

  // MARK: - Shared WKWebView Setup & Zero-Network Origin Bootstrap

  private func createSharedConfiguration() -> WKWebViewConfiguration {
    let cfg = WKWebViewConfiguration()
    cfg.processPool = sharedProcessPool
    cfg.websiteDataStore = .default()
    cfg.applicationNameForUserAgent = "Version/18.2 Safari/605.1.15"
    let prefs = WKWebpagePreferences()
    prefs.allowsContentJavaScript = true
    cfg.defaultWebpagePreferences = prefs
    return cfg
  }

  private func ensureApiWebViewReady(_ completion: @escaping () -> Void) {
    if apiWebView != nil && apiOriginReady {
      completion()
      return
    }
    pendingReadyCallbacks.append(completion)

    if apiWebView == nil {
      let offscreenRect = NSRect(x: -10000, y: -10000, width: 1, height: 1)
      let hostWin = NSWindow(
        contentRect: offscreenRect,
        styleMask: [.borderless],
        backing: .buffered,
        defer: false
      )
      hostWin.isReleasedWhenClosed = false
      hostWin.alphaValue = 0.0
      hostWin.ignoresMouseEvents = true
      self.hiddenHostWindow = hostWin

      let cfg = createSharedConfiguration()
      let wv = WKWebView(frame: NSRect(x: 0, y: 0, width: 1, height: 1), configuration: cfg)
      wv.navigationDelegate = self
      hostWin.contentView?.addSubview(wv)
      self.apiWebView = wv

      bootstrapApiOrigin()

      // Safety fallback so initialize() never blocks past timeout even under heavy load
      DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) { [weak self] in
        self?.markApiOriginReady()
      }
    }
  }

  private func bootstrapApiOrigin() {
    apiOriginReady = false
    let html = "<!doctype html><html><head><meta charset='utf-8'></head><body></body></html>"
    apiWebView?.loadHTMLString(html, baseURL: URL(string: "https://linux.do/"))
  }

  private func markApiOriginReady() {
    guard !apiOriginReady else { return }
    apiOriginReady = true
    let callbacks = pendingReadyCallbacks
    pendingReadyCallbacks.removeAll()
    for cb in callbacks {
      cb()
    }
  }

  // MARK: - HTTP Fetch, Multipart Upload, Binary Fetch, & Cookie Store

  private static func resolveTargetUrl(_ rawUrl: String) -> URL? {
    let trimmed = rawUrl.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return nil }
    if trimmed.hasPrefix("/") {
      return URL(string: "https://linux.do" + trimmed)
    }
    return URL(string: trimmed)
  }

  private func executeFetch(
    method: String,
    rawUrl: String,
    headers: [String: String],
    body: String?,
    result: @escaping FlutterResult
  ) {
    guard let targetUrl = Self.resolveTargetUrl(rawUrl) else {
      result([
        "status": 0,
        "url": rawUrl,
        "headers": [String: String](),
        "body": "",
        "csrfToken": "",
        "error": "Invalid URL",
      ])
      return
    }

    ensureApiWebViewReady { [weak self] in
      guard let self = self, let apiWv = self.apiWebView else {
        result([
          "status": 0,
          "url": targetUrl.absoluteString,
          "headers": [String: String](),
          "body": "",
          "csrfToken": "",
          "error": "WebKit bridge unavailable",
        ])
        return
      }

      let host = (targetUrl.host ?? "").lowercased()
      let scheme = (targetUrl.scheme ?? "").lowercased()
      let isSameOrigin = (scheme == "https" && host == "linux.do")

      if !isSameOrigin {
        self.executeCrossOriginSessionFetch(
          url: targetUrl,
          method: method,
          headers: headers,
          body: body,
          result: result
        )
        return
      }

      let jsScript = """
        const forbidden = new Set([
          'cookie', 'user-agent', 'host', 'origin', 'referer',
          'content-length', 'accept-encoding', 'connection'
        ]);
        const safeHeaders = {};
        let refererUrl = 'https://linux.do/';
        if (reqHeaders && typeof reqHeaders === 'object') {
          for (const [k, v] of Object.entries(reqHeaders)) {
            const lower = String(k).toLowerCase();
            if (lower === 'referer' && v) {
              refererUrl = String(v);
              continue;
            }
            if (!forbidden.has(lower) && !lower.startsWith('sec-')) {
              safeHeaders[k] = String(v);
            }
          }
        }
        const isRead = reqMethod === 'GET' || reqMethod === 'HEAD';
        if (!isRead && !safeHeaders['X-CSRF-Token'] && !safeHeaders['x-csrf-token']) {
          const meta = document.querySelector('meta[name="csrf-token"]');
          const cached = (meta && meta.content) || window.__lindoCsrf || '';
          if (cached) {
            safeHeaders['X-CSRF-Token'] = cached;
          }
        }
        const opts = {
          method: reqMethod,
          headers: safeHeaders,
          credentials: 'include',
          redirect: 'follow',
          referrer: refererUrl
        };
        if (!isRead && reqBody !== null && reqBody !== undefined) {
          opts.body = String(reqBody);
        }
        try {
          const resp = await fetch(reqUrl, opts);
          const text = await resp.text();
          const outHeaders = {};
          resp.headers.forEach((v, k) => {
            outHeaders[String(k).toLowerCase()] = String(v);
          });
          let csrf = outHeaders['x-csrf-token'] || '';
          if (!csrf && text) {
            if (text.trim().startsWith('{')) {
              try {
                const parsed = JSON.parse(text);
                if (parsed && typeof parsed.csrf === 'string') csrf = parsed.csrf;
              } catch (_) {}
            }
            if (!csrf) {
              const m = text.match(/<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']/i)
                     || text.match(/<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']/i);
              if (m && m[1]) csrf = m[1];
            }
          }
          if (!csrf) {
            const meta = document.querySelector('meta[name="csrf-token"]');
            if (meta && meta.content) csrf = meta.content;
            else if (window.__lindoCsrf) csrf = window.__lindoCsrf;
          }
          if (csrf) window.__lindoCsrf = csrf;
          return JSON.stringify({
            ok: true,
            status: resp.status || 0,
            url: resp.url || reqUrl,
            headers: outHeaders,
            body: text,
            csrfToken: csrf
          });
        } catch (err) {
          return JSON.stringify({
            ok: false,
            error: (err && err.message) ? err.message : String(err)
          });
        }
      """

      let jsArgs: [String: Any] = [
        "reqUrl": targetUrl.absoluteString,
        "reqMethod": method,
        "reqHeaders": headers,
        "reqBody": body as Any? ?? NSNull(),
      ]

      apiWv.callAsyncJavaScript(
        jsScript,
        arguments: jsArgs,
        in: nil,
        in: .page
      ) { [weak self] jsResult in
        guard let self = self else { return }
        switch jsResult {
        case .success(let rawOut):
          if let rawStr = rawOut as? String,
             let jsonData = rawStr.data(using: .utf8),
             let dict = (try? JSONSerialization.jsonObject(with: jsonData)) as? [String: Any] {
            if (dict["ok"] as? Bool) == true {
              result([
                "status": (dict["status"] as? Int) ?? 0,
                "url": (dict["url"] as? String) ?? targetUrl.absoluteString,
                "headers": (dict["headers"] as? [String: String]) ?? [:],
                "body": (dict["body"] as? String) ?? "",
                "csrfToken": (dict["csrfToken"] as? String) ?? "",
              ])
              return
            }
            // Fallback to URLSession synced with WKHTTPCookieStore (e.g. if fetch redirected cross-origin to connect.linux.do)
            self.executeCrossOriginSessionFetch(
              url: targetUrl,
              method: method,
              headers: headers,
              body: body,
              result: result
            )
            return
          }
          result([
            "status": 0,
            "url": targetUrl.absoluteString,
            "headers": [String: String](),
            "body": "",
            "csrfToken": "",
            "error": "Unexpected JS bridge payload",
          ])
        case .failure(let jsErr):
          result([
            "status": 0,
            "url": targetUrl.absoluteString,
            "headers": [String: String](),
            "body": "",
            "csrfToken": "",
            "error": jsErr.localizedDescription,
          ])
        }
      }
    }
  }

  private func executeCrossOriginSessionFetch(
    url: URL,
    method: String,
    headers: [String: String],
    body: String?,
    result: @escaping FlutterResult
  ) {
    let store = WKWebsiteDataStore.default().httpCookieStore
    store.getAllCookies { [weak self] cookies in
      guard let self = self else { return }
      let config = URLSessionConfiguration.ephemeral
      config.httpShouldSetCookies = true
      config.httpCookieAcceptPolicy = .always
      config.timeoutIntervalForRequest = 20.0
      if let cookieStorage = config.httpCookieStorage {
        for cookie in cookies {
          cookieStorage.setCookie(cookie)
        }
      }

      var req = URLRequest(url: url)
      req.httpMethod = method
      if !self.cachedUserAgent.isEmpty {
        req.setValue(self.cachedUserAgent, forHTTPHeaderField: "User-Agent")
      }
      for (k, v) in headers {
        if k.lowercased() == "cookie" { continue }
        req.setValue(v, forHTTPHeaderField: k)
      }
      if method != "GET" && method != "HEAD", let body = body {
        req.httpBody = body.data(using: .utf8)
      }

      let session = URLSession(configuration: config)
      let task = session.dataTask(with: req) { data, response, err in
        defer { session.finishTasksAndInvalidate() }
        if let err = err {
          DispatchQueue.main.async {
            result([
              "status": 0,
              "url": url.absoluteString,
              "headers": [String: String](),
              "body": "",
              "csrfToken": "",
              "error": err.localizedDescription,
            ])
          }
          return
        }

        let httpResp = response as? HTTPURLResponse
        let status = httpResp?.statusCode ?? 0
        let finalUrl = httpResp?.url?.absoluteString ?? url.absoluteString
        var outHeaders: [String: String] = [:]
        if let allHeaderFields = httpResp?.allHeaderFields {
          for (k, v) in allHeaderFields {
            outHeaders[String(describing: k).lowercased()] = String(describing: v)
          }
        }

        let bodyStr: String
        if let data = data {
          bodyStr = String(data: data, encoding: .utf8)
            ?? String(data: data, encoding: .isoLatin1)
            ?? ""
        } else {
          bodyStr = ""
        }

        let csrf = Self.extractCsrfToken(fromBody: bodyStr, headers: outHeaders)
        let sessionCookies = config.httpCookieStorage?.cookies ?? []

        DispatchQueue.main.async {
          let wkStore = WKWebsiteDataStore.default().httpCookieStore
          for c in sessionCookies {
            wkStore.setCookie(c, completionHandler: nil)
          }
          result([
            "status": status,
            "url": finalUrl,
            "headers": outHeaders,
            "body": bodyStr,
            "csrfToken": csrf,
          ])
        }
      }
      task.resume()
    }
  }

  private func uploadMultipart(
    rawUrl: String,
    headers: [String: String],
    fields: [String: String],
    fileField: String,
    fileName: String,
    mimeType: String,
    base64Data: String,
    result: @escaping FlutterResult
  ) {
    guard let targetUrl = Self.resolveTargetUrl(rawUrl) else {
      result([
        "status": 0,
        "url": rawUrl,
        "body": "",
        "csrfToken": "",
        "error": "Invalid upload URL",
      ])
      return
    }

    ensureApiWebViewReady { [weak self] in
      guard let apiWv = self?.apiWebView else {
        result([
          "status": 0,
          "url": targetUrl.absoluteString,
          "body": "",
          "csrfToken": "",
          "error": "WebKit bridge unavailable",
        ])
        return
      }

      let jsScript = """
        const forbidden = new Set([
          'cookie', 'user-agent', 'host', 'origin', 'referer',
          'content-length', 'content-type', 'accept-encoding', 'connection'
        ]);
        const safeHeaders = {};
        if (reqHeaders && typeof reqHeaders === 'object') {
          for (const [k, v] of Object.entries(reqHeaders)) {
            const lower = String(k).toLowerCase();
            if (!forbidden.has(lower) && !lower.startsWith('sec-')) {
              safeHeaders[k] = String(v);
            }
          }
        }
        if (!safeHeaders['X-CSRF-Token'] && !safeHeaders['x-csrf-token']) {
          let token = '';
          const meta = document.querySelector('meta[name="csrf-token"]');
          if (meta && meta.content) token = meta.content;
          else if (window.__lindoCsrf) token = window.__lindoCsrf;
          if (!token) {
            try {
              const cr = await fetch('https://linux.do/session/csrf.json', {
                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
                credentials: 'include',
                cache: 'no-store'
              });
              if (cr.ok) {
                const cd = await cr.json();
                if (cd && cd.csrf) {
                  token = cd.csrf;
                  window.__lindoCsrf = token;
                }
              }
            } catch (_) {}
          }
          if (token) safeHeaders['X-CSRF-Token'] = token;
        }

        const binStr = atob(b64Data);
        const bytes = new Uint8Array(binStr.length);
        for (let i = 0; i < binStr.length; i++) {
          bytes[i] = binStr.charCodeAt(i);
        }
        const blob = new Blob([bytes], { type: fileMime || 'application/octet-stream' });
        const formData = new FormData();
        if (extraFields && typeof extraFields === 'object') {
          for (const [k, v] of Object.entries(extraFields)) {
            formData.append(k, String(v));
          }
        }
        formData.append(fieldKey || 'files[]', blob, uploadName || 'upload.bin');

        try {
          const resp = await fetch(reqUrl, {
            method: 'POST',
            headers: safeHeaders,
            body: formData,
            credentials: 'include',
            referrer: 'https://linux.do/'
          });
          const text = await resp.text();
          const csrf = resp.headers.get('x-csrf-token') || window.__lindoCsrf || '';
          if (csrf) window.__lindoCsrf = csrf;
          return JSON.stringify({
            status: resp.status || 0,
            url: resp.url || reqUrl,
            body: text,
            csrfToken: csrf
          });
        } catch (err) {
          return JSON.stringify({
            status: 0,
            url: reqUrl,
            body: '',
            csrfToken: '',
            error: (err && err.message) ? err.message : String(err)
          });
        }
      """

      let jsArgs: [String: Any] = [
        "reqUrl": targetUrl.absoluteString,
        "reqHeaders": headers,
        "extraFields": fields,
        "fieldKey": fileField,
        "uploadName": fileName,
        "fileMime": mimeType,
        "b64Data": base64Data,
      ]

      apiWv.callAsyncJavaScript(
        jsScript,
        arguments: jsArgs,
        in: nil,
        in: .page
      ) { jsResult in
        switch jsResult {
        case .success(let rawOut):
          if let rawStr = rawOut as? String,
             let jsonData = rawStr.data(using: .utf8),
             let dict = (try? JSONSerialization.jsonObject(with: jsonData)) as? [String: Any] {
            var out: [String: Any] = [
              "status": (dict["status"] as? Int) ?? 0,
              "url": (dict["url"] as? String) ?? targetUrl.absoluteString,
              "body": (dict["body"] as? String) ?? "",
              "csrfToken": (dict["csrfToken"] as? String) ?? "",
            ]
            if let errStr = dict["error"] as? String, !errStr.isEmpty {
              out["error"] = errStr
            }
            result(out)
            return
          }
          result([
            "status": 0,
            "url": targetUrl.absoluteString,
            "body": "",
            "csrfToken": "",
            "error": "Multipart upload bridge payload invalid",
          ])
        case .failure(let jsErr):
          result([
            "status": 0,
            "url": targetUrl.absoluteString,
            "body": "",
            "csrfToken": "",
            "error": jsErr.localizedDescription,
          ])
        }
      }
    }
  }

  private func fetchBinary(rawUrl: String, result: @escaping FlutterResult) {
    guard let targetUrl = Self.resolveTargetUrl(rawUrl) else {
      result(nil)
      return
    }

    ensureApiWebViewReady { [weak self] in
      guard let self = self, let apiWv = self.apiWebView else {
        result(nil)
        return
      }

      let host = (targetUrl.host ?? "").lowercased()
      let isSameOrigin = (targetUrl.scheme?.lowercased() == "https" && host == "linux.do")

      if !isSameOrigin {
        self.fetchBinaryViaNativeSession(url: targetUrl, result: result)
        return
      }

      let jsScript = """
        try {
          const resp = await fetch(targetUrl, {
            method: 'GET',
            credentials: 'include',
            referrer: 'https://linux.do/'
          });
          if (!resp.ok) return '';
          const ct = (resp.headers.get('content-type') || '').toLowerCase();
          if (ct.includes('text/html')) return '';
          const buf = await resp.arrayBuffer();
          const bytes = new Uint8Array(buf);
          const chunk = 0x6000;
          const parts = [];
          for (let i = 0; i < bytes.length; i += chunk) {
            parts.push(String.fromCharCode.apply(null, bytes.subarray(i, i + chunk)));
          }
          return btoa(parts.join(''));
        } catch (_) {
          return '';
        }
      """

      apiWv.callAsyncJavaScript(
        jsScript,
        arguments: ["targetUrl": targetUrl.absoluteString],
        in: nil,
        in: .page
      ) { [weak self] jsResult in
        if case .success(let rawOut) = jsResult,
           let b64 = rawOut as? String,
           !b64.isEmpty,
           let data = Data(base64Encoded: b64) {
          result(FlutterStandardTypedData(bytes: data))
          return
        }
        self?.fetchBinaryViaNativeSession(url: targetUrl, result: result)
      }
    }
  }

  private func fetchBinaryViaNativeSession(url: URL, result: @escaping FlutterResult) {
    let store = WKWebsiteDataStore.default().httpCookieStore
    store.getAllCookies { [weak self] cookies in
      guard let self = self else { return }
      let config = URLSessionConfiguration.ephemeral
      config.timeoutIntervalForRequest = 15.0
      if let cookieStorage = config.httpCookieStorage {
        for c in cookies {
          cookieStorage.setCookie(c)
        }
      }
      var req = URLRequest(url: url)
      if !self.cachedUserAgent.isEmpty {
        req.setValue(self.cachedUserAgent, forHTTPHeaderField: "User-Agent")
      }
      let session = URLSession(configuration: config)
      session.dataTask(with: req) { data, resp, _ in
        defer { session.finishTasksAndInvalidate() }
        guard let httpResp = resp as? HTTPURLResponse,
              (200...299).contains(httpResp.statusCode),
              let data = data,
              !data.isEmpty else {
          DispatchQueue.main.async { result(nil) }
          return
        }
        let mime = (httpResp.mimeType ?? "").lowercased()
        if mime.contains("text/html") {
          DispatchQueue.main.async { result(nil) }
          return
        }
        DispatchQueue.main.async {
          result(FlutterStandardTypedData(bytes: data))
        }
      }.resume()
    }
  }

  private func collectCookieHeader(for rawUrl: String, completion: @escaping (String) -> Void) {
    let targetUrl = Self.resolveTargetUrl(rawUrl) ?? URL(string: "https://linux.do/")!
    let targetHost = (targetUrl.host ?? "linux.do").lowercased()
    let store = WKWebsiteDataStore.default().httpCookieStore
    store.getAllCookies { cookies in
      let now = Date()
      var pairs: [String] = []
      for cookie in cookies {
        if let exp = cookie.expiresDate, exp < now {
          continue
        }
        var dom = cookie.domain.lowercased()
        if dom.hasPrefix(".") {
          dom.removeFirst()
        }
        let matches = (targetHost == dom) || targetHost.hasSuffix("." + dom)
        if matches && !cookie.name.isEmpty {
          pairs.append("\(cookie.name)=\(cookie.value)")
        }
      }
      completion(pairs.joined(separator: "; "))
    }
  }

  private func applyRawCookies(_ rawCookieString: String, urlString: String, completion: @escaping () -> Void) {
    let targetUrl = Self.resolveTargetUrl(urlString) ?? URL(string: "https://linux.do/")!
    let defaultHost = (targetUrl.host ?? "linux.do").lowercased()
    let cookiesToSet = Self.parseCookieDefinitions(rawCookieString, defaultHost: defaultHost)
    guard !cookiesToSet.isEmpty else {
      completion()
      return
    }
    let store = WKWebsiteDataStore.default().httpCookieStore
    let group = DispatchGroup()
    for cookie in cookiesToSet {
      group.enter()
      store.setCookie(cookie) {
        group.leave()
      }
    }
    group.notify(queue: .main) {
      completion()
    }
  }

  private static func parseCookieDefinitions(_ raw: String, defaultHost: String) -> [HTTPCookie] {
    let attrNames: Set<String> = [
      "path", "domain", "expires", "max-age", "samesite",
      "secure", "httponly", "priority", "partitioned",
    ]
    var out: [HTTPCookie] = []
    let lines = raw.components(separatedBy: CharacterSet.newlines)
      .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
      .filter { !$0.isEmpty }

    for line in lines {
      let segments = line.split(separator: ";").map {
        $0.trimmingCharacters(in: .whitespacesAndNewlines)
      }.filter { !$0.isEmpty }
      guard !segments.isEmpty else { continue }

      let hasAttributes = segments.dropFirst().contains { seg in
        let key = seg.split(separator: "=", maxSplits: 1).first
          .map { String($0).trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
        return attrNames.contains(key)
      }

      if hasAttributes {
        guard let eqIdx = segments[0].firstIndex(of: "=") else { continue }
        let name = String(segments[0][..<eqIdx]).trimmingCharacters(in: .whitespaces)
        let val = String(segments[0][segments[0].index(after: eqIdx)...]).trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { continue }

        let hostOnlyDefault = (name == "_t" || name == "_forum_session")
        var domain = hostOnlyDefault ? defaultHost : (defaultHost == "linux.do" ? ".linux.do" : defaultHost)
        var path = "/"
        var secure = true
        var httpOnly = (hostOnlyDefault || name == "cf_clearance")

        for attr in segments.dropFirst() {
          let parts = attr.split(separator: "=", maxSplits: 1)
          let k = String(parts[0]).trimmingCharacters(in: .whitespaces).lowercased()
          let v = parts.count > 1 ? String(parts[1]).trimmingCharacters(in: .whitespaces) : ""
          switch k {
          case "domain": if !v.isEmpty { domain = v }
          case "path": if !v.isEmpty { path = v }
          case "secure": secure = true
          case "httponly": httpOnly = true
          default: break
          }
        }
        if let cookie = buildHTTPCookie(name: name, value: val, domain: domain, path: path, secure: secure, httpOnly: httpOnly) {
          out.append(cookie)
        }
      } else {
        for seg in segments {
          guard let eqIdx = seg.firstIndex(of: "=") else { continue }
          let name = String(seg[..<eqIdx]).trimmingCharacters(in: .whitespaces)
          let val = String(seg[seg.index(after: eqIdx)...]).trimmingCharacters(in: .whitespaces)
          guard !name.isEmpty else { continue }
          let hostOnly = (name == "_t" || name == "_forum_session")
          let domain = hostOnly ? defaultHost : (defaultHost == "linux.do" ? ".linux.do" : defaultHost)
          let httpOnly = (hostOnly || name == "cf_clearance")
          if let cookie = buildHTTPCookie(name: name, value: val, domain: domain, path: "/", secure: true, httpOnly: httpOnly) {
            out.append(cookie)
          }
        }
      }
    }
    return out
  }

  private static func buildHTTPCookie(
    name: String,
    value: String,
    domain: String,
    path: String,
    secure: Bool,
    httpOnly: Bool
  ) -> HTTPCookie? {
    var props: [HTTPCookiePropertyKey: Any] = [
      .name: name,
      .value: value,
      .domain: domain,
      .path: path,
      .expires: Date(timeIntervalSinceNow: 86400 * 180),
    ]
    if secure {
      props[.secure] = "TRUE"
    }
    if httpOnly {
      props[HTTPCookiePropertyKey("HttpOnly")] = "TRUE"
    }
    return HTTPCookie(properties: props)
  }

  private func clearSession(completion: @escaping () -> Void) {
    let dataStore = WKWebsiteDataStore.default()
    let allTypes = WKWebsiteDataStore.allWebsiteDataTypes()
    dataStore.removeData(ofTypes: allTypes, modifiedSince: .distantPast) { [weak self] in
      dataStore.httpCookieStore.getAllCookies { cookies in
        let group = DispatchGroup()
        for c in cookies {
          group.enter()
          dataStore.httpCookieStore.delete(c) {
            group.leave()
          }
        }
        group.notify(queue: .main) {
          self?.apiWebView?.evaluateJavaScript("window.__lindoCsrf = null;", completionHandler: nil)
          self?.bootstrapApiOrigin()
          completion()
        }
      }
    }
  }

  // MARK: - Interactive Child Modal WebView & Auto-Detection

  func syncModalAppearance(isDark: Bool) {
    modalIsDark = isDark
    modalWindow?.appearance = NSAppearance(named: isDark ? .darkAqua : .aqua)
  }

  func syncModalFrame() {
    guard let modalWin = modalWindow,
          let parent = parentWindow,
          let contentView = parent.contentView else {
      return
    }
    if !parent.isVisible || parent.isMiniaturized {
      modalWin.orderOut(nil)
      return
    }

    let rectInWin = contentView.convert(contentView.bounds, to: nil)
    let contentScreenRect = parent.convertToScreen(rectInWin)
    let cw = max(1.0, contentScreenRect.width)
    let ch = max(1.0, contentScreenRect.height)

    let wPt = max(1.0, modalNormRect.width * cw)
    let hPt = max(1.0, modalNormRect.height * ch)
    let screenX = contentScreenRect.origin.x + modalNormRect.origin.x * cw
    let screenY = contentScreenRect.origin.y + ch - (modalNormRect.origin.y * ch) - hPt

    let targetFrame = NSRect(x: screenX, y: screenY, width: wPt, height: hPt)
    modalWin.setFrame(targetFrame, display: true)
    modalClipView?.frame = NSRect(x: 0, y: 0, width: wPt, height: hPt)
    modalWebView?.frame = NSRect(x: 0, y: 0, width: wPt, height: hPt)
  }

  private func openModal(url: String, mode: String, normRect: NSRect, isDark: Bool) {
    guard let parent = parentWindow else { return }

    modalGeneration &+= 1
    let activeGen = modalGeneration
    modalMode = mode
    modalNormRect = normRect
    modalIsDark = isDark
    modalCompleted = false
    modalProbeInFlight = false

    if modalWindow == nil {
      let win = LinDoModalPanelWindow(
        contentRect: NSRect(x: 0, y: 0, width: 480, height: 560),
        styleMask: [.borderless],
        backing: .buffered,
        defer: false
      )
      win.isReleasedWhenClosed = false
      win.isOpaque = false
      win.backgroundColor = .clear
      win.hasShadow = false
      win.appearance = NSAppearance(named: isDark ? .darkAqua : .aqua)

      let clip = NSView(frame: NSRect(x: 0, y: 0, width: 480, height: 560))
      clip.wantsLayer = true
      clip.layer?.cornerRadius = 14.0
      clip.layer?.masksToBounds = true
      win.contentView = clip

      let cfg = createSharedConfiguration()
      let wv = WKWebView(frame: clip.bounds, configuration: cfg)
      wv.navigationDelegate = self
      wv.uiDelegate = self
      wv.allowsBackForwardNavigationGestures = true
      clip.addSubview(wv)

      self.modalWindow = win
      self.modalClipView = clip
      self.modalWebView = wv

      parent.addChildWindow(win, ordered: .above)

      moveObserver = NotificationCenter.default.addObserver(
        forName: NSWindow.didMoveNotification,
        object: parent,
        queue: .main
      ) { [weak self] _ in
        self?.syncModalFrame()
      }
      resizeObserver = NotificationCenter.default.addObserver(
        forName: NSWindow.didResizeNotification,
        object: parent,
        queue: .main
      ) { [weak self] _ in
        self?.syncModalFrame()
      }

      progressObservation = wv.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
        guard let self = self else { return }
        DispatchQueue.main.async {
          self.browserChannel.invokeMethod("onModalProgress", arguments: [
            "progress": webView.estimatedProgress,
            "url": webView.url?.absoluteString ?? "",
          ])
        }
      }

      titleObservation = wv.observe(\.title, options: [.new]) { [weak self] webView, _ in
        guard let self = self else { return }
        DispatchQueue.main.async {
          self.browserChannel.invokeMethod("onModalTitleChanged", arguments: [
            "title": webView.title ?? "",
            "url": webView.url?.absoluteString ?? "",
          ])
        }
      }

      urlObservation = wv.observe(\.url, options: [.new]) { [weak self] _, _ in
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
          self?.probeModalCompletionIfNeeded(generation: activeGen)
        }
      }

      if !observingCookieStore {
        observingCookieStore = true
        WKWebsiteDataStore.default().httpCookieStore.add(self)
      }
    } else {
      modalWindow?.appearance = NSAppearance(named: isDark ? .darkAqua : .aqua)
    }

    modalPollTimer?.invalidate()
    modalPollTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
      self?.probeModalCompletionIfNeeded(generation: activeGen)
    }

    syncModalFrame()
    modalWindow?.makeKeyAndOrderFront(nil)

    if let targetUrl = Self.resolveTargetUrl(url) {
      modalWebView?.load(URLRequest(url: targetUrl))
    }
  }

  func closeModal(notifyDart: Bool) {
    modalGeneration &+= 1
    modalCompleted = true
    modalProbeInFlight = false

    modalPollTimer?.invalidate()
    modalPollTimer = nil

    progressObservation?.invalidate()
    progressObservation = nil
    titleObservation?.invalidate()
    titleObservation = nil
    urlObservation?.invalidate()
    urlObservation = nil

    if observingCookieStore {
      observingCookieStore = false
      WKWebsiteDataStore.default().httpCookieStore.remove(self)
    }

    if let obs = moveObserver {
      NotificationCenter.default.removeObserver(obs)
      moveObserver = nil
    }
    if let obs = resizeObserver {
      NotificationCenter.default.removeObserver(obs)
      resizeObserver = nil
    }

    let hadModal = (modalWindow != nil)
    if let wv = modalWebView {
      wv.stopLoading()
      wv.navigationDelegate = nil
      wv.uiDelegate = nil
      wv.removeFromSuperview()
      modalWebView = nil
    }
    modalClipView = nil

    if let win = modalWindow {
      parentWindow?.removeChildWindow(win)
      win.orderOut(nil)
      win.close()
      modalWindow = nil
    }
    if let parent = parentWindow, parent.isVisible {
      parent.makeKey()
    }

    if notifyDart && hadModal {
      browserChannel.invokeMethod("onModalClosed", arguments: [String: Any]())
    }
  }

  func cookiesDidChange(in cookieStore: WKHTTPCookieStore) {
    let gen = modalGeneration
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
      self?.probeModalCompletionIfNeeded(generation: gen)
    }
  }

  private func probeModalCompletionIfNeeded(generation: UInt64) {
    guard generation == modalGeneration,
          !modalCompleted,
          !modalProbeInFlight,
          let wv = modalWebView else {
      return
    }

    let modeLower = modalMode.lowercased()
    let isLoginMode = (modeLower == "login")
    let isVerifyMode = (modeLower == "siteverify" || modeLower == "site_verify" || modeLower == "verify")
    guard isLoginMode || isVerifyMode else { return }

    guard let currentUrl = wv.url,
          let host = currentUrl.host?.lowercased(),
          host == "linux.do" else {
      return
    }

    modalProbeInFlight = true

    let probeScript = """
      const title = document.title || '';
      const isChallenge =
        title.includes('Just a moment') ||
        title.includes('请稍候') ||
        title.includes('Attention Required') ||
        !!document.querySelector('#challenge-running, #challenge-stage, #cf-wrapper, [name="cf-turnstile-response"], iframe[src*="challenges.cloudflare.com"]');
      if (isChallenge) {
        return JSON.stringify({ state: 'challenge' });
      }
      const path = window.location.pathname || '';
      const href = window.location.href || '';
      const meta = document.querySelector('meta[name="csrf-token"]');
      let csrf = (meta && meta.content) ? meta.content : '';

      if (probeMode === 'login') {
        if (path.startsWith('/login') ||
            path.startsWith('/session/') ||
            path.startsWith('/auth/') ||
            path.startsWith('/u/account-created') ||
            path.startsWith('/user-api-key/')) {
          return JSON.stringify({ state: 'pending_login' });
        }
        try {
          const resp = await fetch('/session/current.json', {
            method: 'GET',
            credentials: 'include',
            cache: 'no-store',
            headers: {
              'Accept': 'application/json',
              'X-Requested-With': 'XMLHttpRequest'
            }
          });
          if (!resp.ok) {
            return JSON.stringify({ state: 'unauthenticated' });
          }
          const data = await resp.json();
          const cu = data && data.current_user;
          if (!cu || !cu.username) {
            return JSON.stringify({ state: 'unauthenticated' });
          }
          if (!csrf) {
            try {
              const cr = await fetch('/session/csrf.json', {
                credentials: 'include',
                cache: 'no-store',
                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
              });
              if (cr.ok) {
                const cd = await cr.json();
                if (cd && cd.csrf) csrf = cd.csrf;
              }
            } catch (_) {}
          }
          return JSON.stringify({
            state: 'authenticated',
            url: href,
            csrfToken: csrf,
            user: cu
          });
        } catch (_) {
          return JSON.stringify({ state: 'pending' });
        }
      } else {
        if (document.readyState === 'loading') {
          return JSON.stringify({ state: 'loading' });
        }
        try {
          const sr = await fetch('/srv/status', {
            method: 'GET',
            credentials: 'include',
            cache: 'no-store'
          });
          const cfMit = sr.headers.get('cf-mitigated') || '';
          if (!sr.ok || cfMit) {
            return JSON.stringify({ state: 'challenge' });
          }
          if (!csrf) {
            try {
              const cr = await fetch('/session/csrf.json', {
                credentials: 'include',
                cache: 'no-store',
                headers: { 'Accept': 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
              });
              if (cr.ok) {
                const cd = await cr.json();
                if (cd && cd.csrf) csrf = cd.csrf;
              }
            } catch (_) {}
          }
          return JSON.stringify({
            state: 'verified',
            url: href,
            csrfToken: csrf
          });
        } catch (_) {
          return JSON.stringify({ state: 'pending' });
        }
      }
    """

    wv.callAsyncJavaScript(
      probeScript,
      arguments: ["probeMode": isLoginMode ? "login" : "siteVerify"],
      in: nil,
      in: .page
    ) { [weak self] jsResult in
      guard let self = self else { return }
      self.modalProbeInFlight = false
      guard generation == self.modalGeneration, !self.modalCompleted else { return }

      guard case .success(let rawOut) = jsResult,
            let rawStr = rawOut as? String,
            let data = rawStr.data(using: .utf8),
            let dict = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
            let state = dict["state"] as? String else {
        return
      }

      if state == "authenticated" && isLoginMode {
        self.modalCompleted = true
        let finalUrl = (dict["url"] as? String) ?? currentUrl.absoluteString
        let csrf = (dict["csrfToken"] as? String) ?? ""
        let userDict = (dict["user"] as? [String: Any]) ?? [:]
        if !csrf.isEmpty {
          self.seedApiCsrf(csrf)
        }
        self.collectCookieHeader(for: "https://linux.do/") { [weak self] cookies in
          guard let self = self else { return }
          self.closeModal(notifyDart: false)
          self.browserChannel.invokeMethod("onModalLoginAuthenticated", arguments: [
            "url": finalUrl,
            "cookies": cookies,
            "csrfToken": csrf,
            "user": userDict,
          ])
        }
      } else if state == "verified" && isVerifyMode {
        self.modalCompleted = true
        let finalUrl = (dict["url"] as? String) ?? currentUrl.absoluteString
        let csrf = (dict["csrfToken"] as? String) ?? ""
        if !csrf.isEmpty {
          self.seedApiCsrf(csrf)
        }
        self.collectCookieHeader(for: "https://linux.do/") { [weak self] cookies in
          guard let self = self else { return }
          self.closeModal(notifyDart: false)
          self.browserChannel.invokeMethod("onModalSiteVerified", arguments: [
            "url": finalUrl,
            "cookies": cookies,
            "csrfToken": csrf,
          ])
        }
      }
    }
  }

  private func seedApiCsrf(_ csrf: String) {
    guard !csrf.isEmpty,
          let data = try? JSONSerialization.data(withJSONObject: [csrf]),
          let jsonArr = String(data: data, encoding: .utf8) else {
      return
    }
    apiWebView?.evaluateJavaScript("window.__lindoCsrf = (\(jsonArr))[0];", completionHandler: nil)
  }

  // MARK: - WKNavigationDelegate & WKUIDelegate

  func webView(
    _ webView: WKWebView,
    decidePolicyFor navigationAction: WKNavigationAction,
    decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
  ) {
    guard let url = navigationAction.request.url else {
      decisionHandler(.allow)
      return
    }
    let scheme = (url.scheme ?? "").lowercased()
    if scheme == "lindo" || scheme == "discourse" {
      decisionHandler(.cancel)
      AppDelegate.shared?.dispatchAuthDeepLink(url.absoluteString)
      return
    }

    if webView === modalWebView {
      let allowedSchemes: Set<String> = ["http", "https", "about", "blob", "data", "javascript"]
      if !scheme.isEmpty && !allowedSchemes.contains(scheme) {
        decisionHandler(.cancel)
        NSWorkspace.shared.open(url)
        return
      }
      if navigationAction.targetFrame == nil && (scheme == "http" || scheme == "https") {
        webView.load(navigationAction.request)
        decisionHandler(.cancel)
        return
      }
    }

    decisionHandler(.allow)
  }

  func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
    if webView === modalWebView {
      browserChannel.invokeMethod("onModalPageStarted", arguments: [
        "url": webView.url?.absoluteString ?? "",
      ])
    }
  }

  func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
    if webView === apiWebView {
      if cachedUserAgent.isEmpty {
        webView.evaluateJavaScript("navigator.userAgent") { [weak self] val, _ in
          if let ua = val as? String, !ua.isEmpty {
            self?.cachedUserAgent = ua
          }
          self?.markApiOriginReady()
        }
      } else {
        markApiOriginReady()
      }
    } else if webView === modalWebView {
      let currentUrl = webView.url?.absoluteString ?? ""
      browserChannel.invokeMethod("onModalTitleChanged", arguments: [
        "title": webView.title ?? "",
        "url": currentUrl,
      ])
      browserChannel.invokeMethod("onModalProgress", arguments: [
        "progress": 1.0,
        "url": currentUrl,
      ])
      let gen = modalGeneration
      probeModalCompletionIfNeeded(generation: gen)
    }
  }

  func webView(
    _ webView: WKWebView,
    didFailProvisionalNavigation navigation: WKNavigation!,
    withError error: Error
  ) {
    let nsErr = error as NSError
    if nsErr.code == NSURLErrorCancelled { return }
    if webView === apiWebView {
      markApiOriginReady()
    } else if webView === modalWebView {
      browserChannel.invokeMethod("onModalLoadError", arguments: [
        "url": webView.url?.absoluteString ?? "",
        "message": error.localizedDescription,
      ])
    }
  }

  func webView(
    _ webView: WKWebView,
    didFail navigation: WKNavigation!,
    withError error: Error
  ) {
    let nsErr = error as NSError
    if nsErr.code == NSURLErrorCancelled { return }
    if webView === modalWebView {
      browserChannel.invokeMethod("onModalLoadError", arguments: [
        "url": webView.url?.absoluteString ?? "",
        "message": error.localizedDescription,
      ])
    }
  }

  func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
    if webView === apiWebView {
      bootstrapApiOrigin()
    } else if webView === modalWebView {
      webView.reload()
    }
  }

  func webView(
    _ webView: WKWebView,
    createWebViewWith configuration: WKWebViewConfiguration,
    for navigationAction: WKNavigationAction,
    windowFeatures: WKWindowFeatures
  ) -> WKWebView? {
    if navigationAction.targetFrame == nil, let url = navigationAction.request.url {
      webView.load(URLRequest(url: url))
    }
    return nil
  }

  func webView(
    _ webView: WKWebView,
    runOpenPanelWith parameters: WKOpenPanelParameters,
    initiatedByFrame frame: WKFrameInfo,
    completionHandler: @escaping ([URL]?) -> Void
  ) {
    let panel = NSOpenPanel()
    panel.canChooseFiles = true
    panel.canChooseDirectories = false
    panel.allowsMultipleSelection = parameters.allowsMultipleSelection
    panel.begin { response in
      completionHandler(response == .OK ? panel.urls : nil)
    }
  }

  // MARK: - Helpers

  private static func stringMap(from raw: Any?) -> [String: String] {
    guard let dict = raw as? [AnyHashable: Any] else { return [:] }
    var out: [String: String] = [:]
    for (k, v) in dict {
      out[String(describing: k)] = String(describing: v)
    }
    return out
  }

  private static func extractCsrfToken(fromBody body: String, headers: [String: String]) -> String {
    if let hdr = headers["x-csrf-token"], !hdr.isEmpty {
      return hdr
    }
    let trimmed = body.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.hasPrefix("{"),
       let data = trimmed.data(using: .utf8),
       let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
       let csrf = obj["csrf"] as? String,
       !csrf.isEmpty {
      return csrf
    }
    let patterns = [
      #"<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']"#,
      #"<meta[^>]+content=["']([^"']+)["'][^>]+name=["']csrf-token["']"#,
    ]
    for pattern in patterns {
      if let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
         let match = regex.firstMatch(in: body, range: NSRange(body.startIndex..., in: body)),
         let range = Range(match.range(at: 1), in: body) {
        return String(body[range])
      }
    }
    return ""
  }
}
