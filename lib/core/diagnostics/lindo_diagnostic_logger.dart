import 'dart:collection';
import 'dart:io';

import 'package:flutter/foundation.dart' hide DiagnosticLevel;
import 'package:flutter/scheduler.dart';

import '../copy/lindo_copy.dart';
import '../platform/lindo_platform_channels.dart';

const String kLinDoAppVersionName = '0.3.0';
const int kLinDoAppBuildNumber = 35;

class DiagnosticLogEntry {
  final int id;
  final DateTime timestamp;
  final DiagnosticCategory category;
  final DiagnosticLevel level;
  final String tag;
  final String message;
  final String? detail;

  const DiagnosticLogEntry({
    required this.id,
    required this.timestamp,
    required this.category,
    required this.level,
    required this.tag,
    required this.message,
    this.detail,
  });

  String formatLine() {
    final ts = timestamp.toUtc().toIso8601String();
    final safeTag = tag.trim().isEmpty ? 'General' : tag.trim();
    final singleLineMsg = message.replaceAll('\r\n', '\n').replaceAll('\n', ' | ');
    final header = '[$ts] [${category.wireName}] [${level.wireName}] [$safeTag] $singleLineMsg';
    final cleanDetail = detail?.trim();
    if (cleanDetail == null || cleanDetail.isEmpty) {
      return header;
    }
    final indented = cleanDetail
        .replaceAll('\r\n', '\n')
        .split('\n')
        .map((line) => '  $line')
        .join('\n');
    return '$header\n$indented';
  }
}

class LinDoDiagnosticLogger extends ChangeNotifier {
  static final LinDoDiagnosticLogger instance = LinDoDiagnosticLogger._();

  static const int _maxBufferEntries = 500;
  static const int _maxLogFileBytes = 512 * 1024;
  static const String _activeLogFileName = 'lindo-diagnostics.log';
  static const String _rotatedLogFileName = 'lindo-diagnostics.1.log';

  static final RegExp _lineHeaderRegex = RegExp(
    r'^\[([^\]]+)\]\s+\[(NETWORK|AUTH|CRASH|SYSTEM)\]\s+\[(INFO|WARN|ERROR|CRASH)\]\s+\[([^\]]+)\]\s+(.*)$',
  );

  static final RegExp _cookiePairRegex = RegExp(
    r'\b(_t|_forum_session|cf_clearance|__cf_bm|_cfuvid|cf_chl_rc_i|cf_chl_rc_ni)\s*=\s*[^;\s,"&\x27]+',
    caseSensitive: false,
  );

  static final RegExp _headerSecretRegex = RegExp(
    r'(^|[\r\n,;{\s])(Cookie|Set-Cookie|User-Api-Key|User-Api-Client-Id|X-CSRF-Token|Api-Key|Authorization)\s*:\s*[^\r\n,;\}]+',
    caseSensitive: false,
  );

  static final RegExp _jsonSecretRegex = RegExp(
    r'"(csrf|csrfToken|csrf_token|rawCookies|cookies|api_key|apiKey|user_api_key|password|access_token|refresh_token)"\s*:\s*"[^"]*"',
    caseSensitive: false,
  );

  static final RegExp _otpPathRegex = RegExp(
    r'/session/otp/[A-Za-z0-9_\-]+',
    caseSensitive: false,
  );

  static final RegExp _querySecretRegex = RegExp(
    r'([?&](?:api_key|user_api_key|token|auth_token|csrf|password|otp)=)[^&\s#]+',
    caseSensitive: false,
  );

  LinDoDiagnosticLogger._();

  final ListQueue<DiagnosticLogEntry> _ring = ListQueue<DiagnosticLogEntry>();
  int _nextId = 1;
  Directory? _logsDir;
  File? _activeLogFile;
  File? _rotatedLogFile;
  bool _crashHandlersInstalled = false;
  bool _notifyScheduled = false;
  Future<void> _ioQueue = Future<void>.value();

  List<DiagnosticLogEntry> get entries => List<DiagnosticLogEntry>.unmodifiable(_ring);

  String? get activeLogFilePath => _activeLogFile?.path;

  List<DiagnosticLogEntry> filteredEntries([DiagnosticCategory? category]) {
    if (category == null) {
      return entries;
    }
    return List<DiagnosticLogEntry>.unmodifiable(
      _ring.where((entry) => entry.category == category),
    );
  }

  Future<void> initialize({Directory? stateDir}) async {
    try {
      final baseDir = stateDir ?? await LinDoPlatformBridge.instance.resolveStateDirectory();
      final logsDir = Directory('${baseDir.path}${Platform.pathSeparator}logs');
      if (!logsDir.existsSync()) {
        logsDir.createSync(recursive: true);
      }
      _logsDir = logsDir;
      _activeLogFile = File('${logsDir.path}${Platform.pathSeparator}$_activeLogFileName');
      _rotatedLogFile = File('${logsDir.path}${Platform.pathSeparator}$_rotatedLogFileName');

      if (_activeLogFile!.existsSync()) {
        final stat = _activeLogFile!.statSync();
        if (stat.size > _maxLogFileBytes) {
          await _rotateLogFileNow();
        } else {
          final rawContent = _activeLogFile!.readAsStringSync();
          _hydrateFromPersistedLog(rawContent);
        }
      }
      _notifySafely();
    } catch (err) {
      debugPrint('LinDoDiagnosticLogger.initialize failed: $err');
    }
  }

  void installGlobalCrashHandlers() {
    if (_crashHandlersInstalled) return;
    _crashHandlersInstalled = true;

    final previousFlutterOnError = FlutterError.onError;
    FlutterError.onError = (FlutterErrorDetails details) {
      crash(
        'FlutterError',
        details.exception,
        details.stack ?? StackTrace.current,
      );
      if (previousFlutterOnError != null) {
        previousFlutterOnError(details);
      } else {
        FlutterError.presentError(details);
      }
    };

    final previousPlatformOnError = PlatformDispatcher.instance.onError;
    PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {
      crash('PlatformDispatcher', error, stack);
      if (previousPlatformOnError != null) {
        return previousPlatformOnError(error, stack);
      }
      return true;
    };
  }

  void info(
    DiagnosticCategory category,
    String tag,
    String message, {
    String? detail,
  }) {
    _appendEntry(
      category: category,
      level: DiagnosticLevel.INFO,
      tag: tag,
      message: message,
      detail: detail,
    );
  }

  void warn(
    DiagnosticCategory category,
    String tag,
    String message, {
    String? detail,
  }) {
    _appendEntry(
      category: category,
      level: DiagnosticLevel.WARN,
      tag: tag,
      message: message,
      detail: detail,
    );
  }

  void error(
    DiagnosticCategory category,
    String tag,
    String message, {
    Object? error,
    StackTrace? stackTrace,
    String? detail,
  }) {
    final detailParts = <String>[
      if (detail != null && detail.trim().isNotEmpty) detail.trim(),
      if (error != null) 'Error: $error',
      if (stackTrace != null) _trimStackTrace(stackTrace),
    ];
    _appendEntry(
      category: category,
      level: DiagnosticLevel.ERROR,
      tag: tag,
      message: message,
      detail: detailParts.isEmpty ? null : detailParts.join('\n'),
    );
  }

  void crash(String tag, Object error, StackTrace stackTrace) {
    _appendEntry(
      category: DiagnosticCategory.CRASH,
      level: DiagnosticLevel.CRASH,
      tag: tag,
      message: error.toString(),
      detail: _trimStackTrace(stackTrace),
      deferNotifyIfBuilding: true,
    );
  }

  String redactSensitive(String input) {
    if (input.isEmpty) return input;
    var out = input;
    out = out.replaceAllMapped(_headerSecretRegex, (match) {
      final prefix = match.group(1) ?? '';
      final headerName = match.group(2) ?? 'Secret';
      return '$prefix$headerName: [REDACTED]';
    });
    out = out.replaceAllMapped(_cookiePairRegex, (match) {
      final cookieName = match.group(1) ?? 'cookie';
      return '$cookieName=[REDACTED]';
    });
    out = out.replaceAllMapped(_jsonSecretRegex, (match) {
      final fieldName = match.group(1) ?? 'secret';
      return '"$fieldName":"[REDACTED]"';
    });
    out = out.replaceAll(_otpPathRegex, '/session/otp/[REDACTED]');
    out = out.replaceAllMapped(_querySecretRegex, (match) {
      final prefix = match.group(1) ?? '';
      return '$prefix[REDACTED]';
    });
    return out;
  }

  String buildDiagnosticReport({
    String? username,
    bool isAuthorized = false,
  }) {
    final nowUtc = DateTime.now().toUtc().toIso8601String();
    final safeUser = (username == null || username.trim().isEmpty)
        ? 'guest'
        : redactSensitive(username.trim());
    final osSummary = '${Platform.operatingSystem} (${Platform.operatingSystemVersion})';
    final dartSummary = Platform.version;
    final buffer = StringBuffer()
      ..writeln('=== Lindo Diagnostic Report ===')
      ..writeln('Generated: $nowUtc')
      ..writeln('Version: $kLinDoAppVersionName+$kLinDoAppBuildNumber')
      ..writeln('Platform: $osSummary')
      ..writeln('Dart: $dartSummary')
      ..writeln('Auth: ${isAuthorized ? "authorized" : "guest"} (user: $safeUser)')
      ..writeln('LogDirectory: ${_logsDir?.path ?? "uninitialized"}')
      ..writeln('Entries: ${_ring.length}')
      ..writeln('================================');

    for (final entry in _ring) {
      buffer.writeln(redactSensitive(entry.formatLine()));
    }
    return buffer.toString();
  }

  Future<void> clearLogs() async {
    _ring.clear();
    await _enqueueIo(() async {
      try {
        if (_activeLogFile != null && _activeLogFile!.existsSync()) {
          _activeLogFile!.deleteSync();
        }
      } catch (_) {}
      try {
        if (_rotatedLogFile != null && _rotatedLogFile!.existsSync()) {
          _rotatedLogFile!.deleteSync();
        }
      } catch (_) {}
    });
    _notifySafely();
  }

  void _appendEntry({
    required DiagnosticCategory category,
    required DiagnosticLevel level,
    required String tag,
    required String message,
    String? detail,
    bool deferNotifyIfBuilding = false,
  }) {
    final entry = DiagnosticLogEntry(
      id: _nextId++,
      timestamp: DateTime.now(),
      category: category,
      level: level,
      tag: redactSensitive(tag.trim().isEmpty ? 'General' : tag.trim()),
      message: redactSensitive(message),
      detail: detail == null ? null : redactSensitive(detail),
    );

    if (_ring.length >= _maxBufferEntries) {
      _ring.removeFirst();
    }
    _ring.addLast(entry);

    final serialized = '${entry.formatLine()}\n';
    _enqueueIo(() => _persistLine(serialized));
    _notifySafely(deferIfBuilding: deferNotifyIfBuilding);
  }

  Future<void> _enqueueIo(Future<void> Function() task) {
    final next = _ioQueue.then((_) => task()).catchError((Object _) {});
    _ioQueue = next;
    return next;
  }

  Future<void> _persistLine(String serialized) async {
    final active = _activeLogFile;
    if (active == null) return;
    try {
      if (active.existsSync()) {
        final currentLen = active.lengthSync();
        if (currentLen + serialized.length > _maxLogFileBytes) {
          await _rotateLogFileNow();
        }
      } else {
        active.parent.createSync(recursive: true);
      }
      active.writeAsStringSync(serialized, mode: FileMode.append, flush: true);
    } catch (_) {}
  }

  Future<void> _rotateLogFileNow() async {
    final active = _activeLogFile;
    final rotated = _rotatedLogFile;
    if (active == null || rotated == null) return;
    try {
      if (rotated.existsSync()) {
        rotated.deleteSync();
      }
      if (active.existsSync()) {
        active.renameSync(rotated.path);
      }
    } catch (_) {
      try {
        if (active.existsSync()) {
          active.writeAsStringSync('', mode: FileMode.write, flush: true);
        }
      } catch (_) {}
    }
  }

  void _hydrateFromPersistedLog(String content) {
    if (content.trim().isEmpty) return;
    final parsed = <DiagnosticLogEntry>[];
    String? pendingTs;
    DiagnosticCategory? pendingCat;
    DiagnosticLevel? pendingLvl;
    String? pendingTag;
    String? pendingMsg;
    final detailLines = <String>[];

    void flushPending() {
      if (pendingCat == null || pendingLvl == null || pendingTag == null || pendingMsg == null) {
        return;
      }
      final ts = DateTime.tryParse(pendingTs ?? '') ?? DateTime.now();
      parsed.add(
        DiagnosticLogEntry(
          id: _nextId++,
          timestamp: ts,
          category: pendingCat!,
          level: pendingLvl!,
          tag: redactSensitive(pendingTag!),
          message: redactSensitive(pendingMsg!),
          detail: detailLines.isEmpty ? null : redactSensitive(detailLines.join('\n')),
        ),
      );
      pendingTs = null;
      pendingCat = null;
      pendingLvl = null;
      pendingTag = null;
      pendingMsg = null;
      detailLines.clear();
    }

    for (final rawLine in content.replaceAll('\r\n', '\n').split('\n')) {
      if (rawLine.isEmpty) continue;
      final match = _lineHeaderRegex.firstMatch(rawLine);
      if (match != null) {
        flushPending();
        pendingTs = match.group(1);
        pendingCat = _parseCategory(match.group(2));
        pendingLvl = _parseLevel(match.group(3));
        pendingTag = match.group(4) ?? 'General';
        pendingMsg = match.group(5) ?? '';
      } else if (pendingMsg != null) {
        detailLines.add(rawLine.startsWith('  ') ? rawLine.substring(2) : rawLine);
      }
    }
    flushPending();

    final startIndex = parsed.length > _maxBufferEntries
        ? parsed.length - _maxBufferEntries
        : 0;
    for (var i = startIndex; i < parsed.length; i++) {
      if (_ring.length >= _maxBufferEntries) {
        _ring.removeFirst();
      }
      _ring.addLast(parsed[i]);
    }
  }

  static DiagnosticCategory _parseCategory(String? wire) {
    for (final cat in DiagnosticCategory.values) {
      if (cat.wireName == wire) return cat;
    }
    return DiagnosticCategory.SYSTEM;
  }

  static DiagnosticLevel _parseLevel(String? wire) {
    for (final lvl in DiagnosticLevel.values) {
      if (lvl.wireName == wire) return lvl;
    }
    return DiagnosticLevel.INFO;
  }

  static String _trimStackTrace(StackTrace stackTrace, {int maxLines = 24}) {
    final lines = stackTrace.toString().replaceAll('\r\n', '\n').split('\n');
    if (lines.length <= maxLines) {
      return lines.join('\n').trim();
    }
    return '${lines.take(maxLines).join('\n')}\n... (${lines.length - maxLines} more frames)';
  }

  void _notifySafely({bool deferIfBuilding = false}) {
    if (deferIfBuilding) {
      try {
        final phase = SchedulerBinding.instance.schedulerPhase;
        if (phase == SchedulerPhase.persistentCallbacks ||
            phase == SchedulerPhase.midFrameMicrotasks) {
          if (!_notifyScheduled) {
            _notifyScheduled = true;
            SchedulerBinding.instance.addPostFrameCallback((_) {
              _notifyScheduled = false;
              notifyListeners();
            });
          }
          return;
        }
      } catch (_) {
        // SchedulerBinding not initialized (e.g., pure Dart test).
      }
    }
    try {
      notifyListeners();
    } catch (_) {}
  }
}
