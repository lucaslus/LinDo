import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../models/lindo_models.dart';

class BrowserHttpResponse {
  final int status;
  final String url;
  final Map<String, String> headers;
  final String body;
  final String csrfToken;
  final String? error;

  const BrowserHttpResponse({
    required this.status,
    this.url = '',
    this.headers = const {},
    this.body = '',
    this.csrfToken = '',
    this.error,
  });

  bool get isOk => status >= 200 && status < 300;
}

enum BrowserModalEventKind {
  pageStarted,
  progress,
  titleChanged,
  loginAuthenticated,
  siteVerified,
  loadError,
  closed,
}

class BrowserModalEvent {
  final BrowserModalEventKind kind;
  final String url;
  final String title;
  final double progress;
  final String rawCookies;
  final String csrfToken;
  final Map<String, dynamic>? userPayload;
  final String? errorMessage;

  const BrowserModalEvent({
    required this.kind,
    this.url = '',
    this.title = '',
    this.progress = 0.0,
    this.rawCookies = '',
    this.csrfToken = '',
    this.userPayload,
    this.errorMessage,
  });
}

/// 跨端统一原生浏览器通道 (`do_.linux.lindo/browser`)：
/// - macOS: 原生 `WKWebView` + `WKHTTPCookieStore` + 圆角 AppKit 子窗口
/// - Windows: 原生 `ICoreWebView2` + `ICoreWebView2CookieManager` + 圆角 Win32 子窗口
/// - Android: 原生 `android.webkit.WebView` + `CookieManager` + 圆角卡片容器
/// - 测试环境下自动降级为纯 Dart 内存 / Mock HTTP 通道，零真实网络访问。
class LinDoBrowserBridge {
  LinDoBrowserBridge._() {
    _channel.setMethodCallHandler(_handleNativeEvent);
  }

  static final LinDoBrowserBridge instance = LinDoBrowserBridge._();
  static const MethodChannel _channel = MethodChannel('do_.linux.lindo/browser');

  final StreamController<BrowserModalEvent> _modalEvents =
      StreamController<BrowserModalEvent>.broadcast();

  Stream<BrowserModalEvent> get modalEvents => _modalEvents.stream;

  /// 测试环境中可显式关闭或注入模拟执行器
  @visibleForTesting
  bool? debugForceAvailable;

  @visibleForTesting
  Future<BrowserHttpResponse> Function({
    required String method,
    required String url,
    required Map<String, String> headers,
    String? body,
  })? debugFetchOverride;

  bool _initialized = false;
  bool _nativeSupported = false;
  String _browserUserAgent = '';

  String get browserUserAgent => _browserUserAgent;
  bool get isAvailable => debugForceAvailable ?? _nativeSupported;

  Future<bool> initialize() async {
    if (debugForceAvailable != null) return debugForceAvailable!;
    if (_initialized) return _nativeSupported;
    _initialized = true;
    if (kIsWeb) {
      _nativeSupported = false;
      return false;
    }
    try {
      final res = await _channel
          .invokeMapMethod<String, dynamic>('initialize')
          .timeout(const Duration(seconds: 6));
      _nativeSupported = res?['ok'] == true;
      _browserUserAgent = (res?['userAgent'] as String?) ?? '';
      return _nativeSupported;
    } catch (_) {
      _nativeSupported = false;
      return false;
    }
  }

  Future<BrowserHttpResponse> executeFetch({
    required String method,
    required String url,
    Map<String, String> headers = const {},
    String? body,
  }) async {
    if (debugFetchOverride != null) {
      return debugFetchOverride!(
        method: method,
        url: url,
        headers: headers,
        body: body,
      );
    }
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>(
        'executeFetch',
        {
          'method': method,
          'url': url,
          'headers': headers,
          'body': body,
        },
      );
      if (res == null) {
        return const BrowserHttpResponse(status: 0, error: 'Empty bridge response');
      }
      final rawHeaders = <String, String>{};
      final hdrMap = res['headers'];
      if (hdrMap is Map) {
        for (final entry in hdrMap.entries) {
          rawHeaders[entry.key.toString().toLowerCase()] =
              entry.value?.toString() ?? '';
        }
      }
      return BrowserHttpResponse(
        status: (res['status'] as num?)?.toInt() ?? 0,
        url: (res['url'] as String?) ?? url,
        headers: rawHeaders,
        body: (res['body'] as String?) ?? '',
        csrfToken: (res['csrfToken'] as String?) ?? '',
        error: res['error'] as String?,
      );
    } catch (e) {
      return BrowserHttpResponse(status: 0, url: url, error: e.toString());
    }
  }

  Future<BrowserHttpResponse> uploadMultipart({
    required String url,
    required Map<String, String> headers,
    required Map<String, String> fields,
    required String fileField,
    required String fileName,
    required String mimeType,
    required Uint8List bytes,
  }) async {
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>(
        'uploadMultipart',
        {
          'url': url,
          'headers': headers,
          'fields': fields,
          'fileField': fileField,
          'fileName': fileName,
          'mimeType': mimeType,
          'base64Data': base64Encode(bytes),
        },
      );
      if (res == null) {
        return const BrowserHttpResponse(status: 0, error: 'Empty upload response');
      }
      return BrowserHttpResponse(
        status: (res['status'] as num?)?.toInt() ?? 0,
        url: (res['url'] as String?) ?? url,
        body: (res['body'] as String?) ?? '',
        csrfToken: (res['csrfToken'] as String?) ?? '',
        error: res['error'] as String?,
      );
    } catch (e) {
      return BrowserHttpResponse(status: 0, url: url, error: e.toString());
    }
  }

  Future<Uint8List?> fetchBinary(String url) async {
    if (!_nativeSupported) return null;
    try {
      final res = await _channel.invokeMethod<dynamic>('fetchBinary', {'url': url});
      if (res is Uint8List) return res;
      if (res is String && res.isNotEmpty) return base64Decode(res);
      return null;
    } catch (_) {
      return null;
    }
  }

  Future<String> getCookies([String url = 'https://linux.do']) async {
    if (!_nativeSupported) return '';
    try {
      return (await _channel.invokeMethod<String>('getCookies', {'url': url})) ?? '';
    } catch (_) {
      return '';
    }
  }

  Future<void> setCookies(String rawCookieString,
      {String url = 'https://linux.do'}) async {
    if (!_nativeSupported || rawCookieString.trim().isEmpty) return;
    try {
      await _channel.invokeMethod<void>('setCookies', {
        'url': url,
        'cookies': rawCookieString,
      });
    } catch (_) {}
  }

  Future<void> clearSession() async {
    if (!_nativeSupported) return;
    try {
      await _channel.invokeMethod<void>('clearSession');
    } catch (_) {}
  }

  /// 在卡片归一化坐标 (`[0..1]` 相对窗口内容区) 打开内置原生 WebView
  Future<void> openInteractiveModal({
    required String url,
    required String mode,
    required double normLeft,
    required double normTop,
    required double normWidth,
    required double normHeight,
    required bool isDark,
  }) async {
    if (!_nativeSupported) return;
    try {
      await _channel.invokeMethod<void>('openModal', {
        'url': url,
        'mode': mode,
        'left': normLeft.clamp(0.0, 1.0),
        'top': normTop.clamp(0.0, 1.0),
        'width': normWidth.clamp(0.05, 1.0),
        'height': normHeight.clamp(0.05, 1.0),
        'isDark': isDark,
      });
    } catch (_) {}
  }

  Future<void> updateModalRect({
    required double normLeft,
    required double normTop,
    required double normWidth,
    required double normHeight,
  }) async {
    if (!_nativeSupported) return;
    try {
      await _channel.invokeMethod<void>('updateModalRect', {
        'left': normLeft.clamp(0.0, 1.0),
        'top': normTop.clamp(0.0, 1.0),
        'width': normWidth.clamp(0.05, 1.0),
        'height': normHeight.clamp(0.05, 1.0),
      });
    } catch (_) {}
  }

  Future<void> reloadModal() async {
    if (!_nativeSupported) return;
    try {
      await _channel.invokeMethod<void>('reloadModal');
    } catch (_) {}
  }

  Future<void> goBackModal() async {
    if (!_nativeSupported) return;
    try {
      await _channel.invokeMethod<void>('goBackModal');
    } catch (_) {}
  }

  Future<void> closeModal() async {
    if (!_nativeSupported) return;
    try {
      await _channel.invokeMethod<void>('closeModal');
    } catch (_) {}
  }

  @visibleForTesting
  void emitDebugModalEvent(BrowserModalEvent event) {
    _modalEvents.add(event);
  }

  Future<dynamic> _handleNativeEvent(MethodCall call) async {
    final args = (call.arguments is Map)
        ? Map<String, dynamic>.from(call.arguments as Map)
        : const <String, dynamic>{};
    switch (call.method) {
      case 'onModalPageStarted':
        _modalEvents.add(BrowserModalEvent(
          kind: BrowserModalEventKind.pageStarted,
          url: (args['url'] as String?) ?? '',
        ));
        break;
      case 'onModalProgress':
        _modalEvents.add(BrowserModalEvent(
          kind: BrowserModalEventKind.progress,
          progress: (args['progress'] as num?)?.toDouble() ?? 0.0,
          url: (args['url'] as String?) ?? '',
        ));
        break;
      case 'onModalTitleChanged':
        _modalEvents.add(BrowserModalEvent(
          kind: BrowserModalEventKind.titleChanged,
          title: (args['title'] as String?) ?? '',
          url: (args['url'] as String?) ?? '',
        ));
        break;
      case 'onModalLoginAuthenticated':
        Map<String, dynamic>? userMap;
        final rawUser = args['user'];
        if (rawUser is Map) {
          userMap = Map<String, dynamic>.from(rawUser);
        } else if (rawUser is String && rawUser.trim().startsWith('{')) {
          try {
            userMap = jsonDecode(rawUser) as Map<String, dynamic>;
          } catch (_) {}
        }
        _modalEvents.add(BrowserModalEvent(
          kind: BrowserModalEventKind.loginAuthenticated,
          url: (args['url'] as String?) ?? '',
          rawCookies: (args['cookies'] as String?) ?? '',
          csrfToken: (args['csrfToken'] as String?) ?? '',
          userPayload: userMap,
        ));
        break;
      case 'onModalSiteVerified':
        _modalEvents.add(BrowserModalEvent(
          kind: BrowserModalEventKind.siteVerified,
          url: (args['url'] as String?) ?? '',
          rawCookies: (args['cookies'] as String?) ?? '',
          csrfToken: (args['csrfToken'] as String?) ?? '',
        ));
        break;
      case 'onModalLoadError':
        _modalEvents.add(BrowserModalEvent(
          kind: BrowserModalEventKind.loadError,
          url: (args['url'] as String?) ?? '',
          errorMessage: (args['message'] as String?) ?? '',
        ));
        break;
      case 'onModalClosed':
        _modalEvents.add(const BrowserModalEvent(
          kind: BrowserModalEventKind.closed,
        ));
        break;
    }
    return null;
  }
}

/// 跨端系统宿主能力通道 (`do_.linux.lindo/platform`)：
/// - 窗口沉浸式标题栏内边距 (`macTitleBarInset`) 与深浅色外观同步
/// - macOS MenuBar / Windows 系统托盘角标与快捷命令
/// - Android 动态 `activity-alias` 应用图标切换
/// - 诊断日志 `.log` 保存对话框、附件文件选择器与外部浏览器打开
class LinDoPlatformBridge {
  LinDoPlatformBridge._() {
    _channel.setMethodCallHandler(_handlePlatformCall);
  }

  static final LinDoPlatformBridge instance = LinDoPlatformBridge._();
  static const MethodChannel _channel = MethodChannel('do_.linux.lindo/platform');

  final StreamController<DesktopTrayCommand> _trayCommands =
      StreamController<DesktopTrayCommand>.broadcast();
  final StreamController<double> _macTitleBarInsets =
      StreamController<double>.broadcast();
  final StreamController<String> _authDeepLinks =
      StreamController<String>.broadcast();

  Stream<DesktopTrayCommand> get trayCommands => _trayCommands.stream;
  Stream<double> get macTitleBarInsets => _macTitleBarInsets.stream;
  Stream<String> get authDeepLinks => _authDeepLinks.stream;

  double _currentMacInset = (!kIsWeb && Platform.isMacOS) ? 28.0 : 0.0;
  double get currentMacTitleBarInset => _currentMacInset;

  @visibleForTesting
  Directory? debugStateDirOverride;

  Future<Directory> resolveStateDirectory() async {
    if (debugStateDirOverride != null) {
      if (!debugStateDirOverride!.existsSync()) {
        debugStateDirOverride!.createSync(recursive: true);
      }
      return debugStateDirOverride!;
    }
    try {
      final path = await _channel.invokeMethod<String>('getStateDirectory');
      if (path != null && path.trim().isNotEmpty) {
        final dir = Directory(path);
        if (!dir.existsSync()) dir.createSync(recursive: true);
        return dir;
      }
    } catch (_) {}
    final home = Platform.environment['HOME'] ??
        Platform.environment['USERPROFILE'] ??
        Directory.systemTemp.path;
    final fallback = Directory('$home/.lindo_flutter');
    if (!fallback.existsSync()) fallback.createSync(recursive: true);
    return fallback;
  }

  Future<void> syncWindowTheme({required bool isDark}) async {
    if (debugStateDirOverride != null) return;
    try {
      await _channel.invokeMethod<void>('syncWindowTheme', {'isDark': isDark});
    } catch (_) {}
  }

  Future<void> updateTrayState({
    required int unreadCount,
    String? username,
  }) async {
    if (debugStateDirOverride != null) return;
    if (kIsWeb || (!Platform.isMacOS && !Platform.isWindows)) return;
    try {
      await _channel.invokeMethod<void>('updateTrayState', {
        'unreadCount': unreadCount,
        'username': username ?? '',
      });
    } catch (_) {}
  }

  Future<bool> applyLauncherIcon(String iconKey) async {
    if (kIsWeb || !Platform.isAndroid) return true;
    try {
      final ok = await _channel.invokeMethod<bool>(
        'applyLauncherIcon',
        {'iconKey': iconKey},
      );
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  Future<String?> saveDiagnosticFile({
    required String suggestedName,
    required String content,
  }) async {
    try {
      return await _channel.invokeMethod<String>('saveDiagnosticFile', {
        'suggestedName': suggestedName,
        'content': content,
      });
    } catch (_) {
      return null;
    }
  }

  Future<Map<String, dynamic>?> pickAttachmentFile() async {
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>('pickAttachmentFile');
      return res;
    } catch (_) {
      return null;
    }
  }

  Future<bool> openExternalUrl(String url) async {
    try {
      final ok = await _channel.invokeMethod<bool>('openExternalUrl', {'url': url});
      if (ok == true) return true;
    } catch (_) {}
    if (!kIsWeb && Platform.isMacOS) {
      await Process.run('open', [url]);
      return true;
    }
    if (!kIsWeb && Platform.isWindows) {
      await Process.run('rundll32', ['url.dll,FileProtocolHandler', url]);
      return true;
    }
    return false;
  }

  Future<dynamic> _handlePlatformCall(MethodCall call) async {
    final args = (call.arguments is Map)
        ? Map<String, dynamic>.from(call.arguments as Map)
        : const <String, dynamic>{};
    switch (call.method) {
      case 'onTrayCommand':
        final cmd = args['command'] as String?;
        if (cmd == 'open_messages') {
          _trayCommands.add(DesktopTrayCommand.OPEN_MESSAGES);
        } else if (cmd == 'open_publish') {
          _trayCommands.add(DesktopTrayCommand.OPEN_PUBLISH);
        }
        break;
      case 'onMacTitleBarInset':
        final inset = (args['inset'] as num?)?.toDouble() ?? 0.0;
        _currentMacInset = inset;
        _macTitleBarInsets.add(inset);
        break;
      case 'onAuthDeepLink':
        final uri = args['uri'] as String?;
        if (uri != null && uri.isNotEmpty) {
          _authDeepLinks.add(uri);
        }
        break;
    }
    return null;
  }
}
