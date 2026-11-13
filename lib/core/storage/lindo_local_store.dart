import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/foundation.dart';

import '../../ui/theme/lindo_theme.dart';
import '../models/lindo_models.dart';
import '../platform/lindo_platform_channels.dart';

class LinDoUserPreferences {
  final bool? isDarkOverride;
  final BrandThemeOption brandTheme;
  final bool isCompactFeed;
  final double fontScale;
  final bool enableAnimations;
  final LinDoAppIcon appIcon;
  final bool debugModeUnlocked;
  final bool hasCompletedWelcome;

  const LinDoUserPreferences({
    this.isDarkOverride,
    this.brandTheme = BrandThemeOption.OFFICIAL_GOLD,
    this.isCompactFeed = false,
    this.fontScale = 1.0,
    this.enableAnimations = true,
    this.appIcon = LinDoAppIcon.defaultIcon,
    this.debugModeUnlocked = false,
    this.hasCompletedWelcome = false,
  });

  LinDoUserPreferences copyWith({
    bool? isDarkOverride,
    bool clearDarkOverride = false,
    BrandThemeOption? brandTheme,
    bool? isCompactFeed,
    double? fontScale,
    bool? enableAnimations,
    LinDoAppIcon? appIcon,
    bool? debugModeUnlocked,
    bool? hasCompletedWelcome,
  }) =>
      LinDoUserPreferences(
        isDarkOverride:
            clearDarkOverride ? null : (isDarkOverride ?? this.isDarkOverride),
        brandTheme: brandTheme ?? this.brandTheme,
        isCompactFeed: isCompactFeed ?? this.isCompactFeed,
        fontScale: fontScale ?? this.fontScale,
        enableAnimations: enableAnimations ?? this.enableAnimations,
        appIcon: appIcon ?? this.appIcon,
        debugModeUnlocked: debugModeUnlocked ?? this.debugModeUnlocked,
        hasCompletedWelcome: hasCompletedWelcome ?? this.hasCompletedWelcome,
      );

  Map<String, dynamic> toJson() => <String, dynamic>{
        'isDarkOverride': isDarkOverride,
        'brandTheme': brandTheme.key,
        'isCompactFeed': isCompactFeed,
        'fontScale': fontScale,
        'enableAnimations': enableAnimations,
        'appIcon': appIcon.key,
        'debugModeUnlocked': debugModeUnlocked,
        'hasCompletedWelcome': hasCompletedWelcome,
      };

  factory LinDoUserPreferences.fromJson(Map<String, dynamic> json) {
    final rawScale = (json['fontScale'] as num?)?.toDouble() ?? 1.0;
    return LinDoUserPreferences(
      isDarkOverride: json['isDarkOverride'] as bool?,
      brandTheme: BrandThemeOption.fromKey(json['brandTheme'] as String?),
      isCompactFeed: (json['isCompactFeed'] as bool?) ?? false,
      fontScale: rawScale.clamp(0.85, 1.35),
      enableAnimations: (json['enableAnimations'] as bool?) ?? true,
      appIcon: LinDoAppIcon.fromKey(json['appIcon'] as String?),
      debugModeUnlocked: (json['debugModeUnlocked'] as bool?) ?? false,
      hasCompletedWelcome: (json['hasCompletedWelcome'] as bool?) ?? false,
    );
  }
}

class ReadHistoryEntry {
  final int topicId;
  final String title;
  final String categoryName;
  final String categoryColorHex;
  final int lastReadPostNumber;
  final int postsCount;
  final int visitedAtMs;

  const ReadHistoryEntry({
    required this.topicId,
    required this.title,
    required this.categoryName,
    this.categoryColorHex = '0088CC',
    required this.lastReadPostNumber,
    required this.postsCount,
    required this.visitedAtMs,
  });

  Map<String, dynamic> toJson() => <String, dynamic>{
        'topicId': topicId,
        'title': title,
        'categoryName': categoryName,
        'categoryColorHex': categoryColorHex,
        'lastReadPostNumber': lastReadPostNumber,
        'postsCount': postsCount,
        'visitedAtMs': visitedAtMs,
      };

  factory ReadHistoryEntry.fromJson(Map<String, dynamic> json) => ReadHistoryEntry(
        topicId: (json['topicId'] as num?)?.toInt() ?? 0,
        title: (json['title'] as String?) ?? '',
        categoryName: (json['categoryName'] as String?) ?? '',
        categoryColorHex: (json['categoryColorHex'] as String?) ?? '0088CC',
        lastReadPostNumber: (json['lastReadPostNumber'] as num?)?.toInt() ?? 1,
        postsCount: (json['postsCount'] as num?)?.toInt() ?? 1,
        visitedAtMs: (json['visitedAtMs'] as num?)?.toInt() ?? 0,
      );
}

class LinDoLocalStore extends ChangeNotifier {
  static const int _maxReadHistoryItems = 250;
  static const String _prefsFileName = 'preferences.json';
  static const String _sessionFileName = 'auth_session.json';
  static const String _historyFileName = 'read_history.json';
  static const String _draftsFileName = 'local_drafts.json';
  static const String _categoriesCacheFileName = 'categories.json';
  static const String _emojisCacheFileName = 'emojis.json';

  final Directory? _stateDirOverride;
  Directory? _stateDir;
  Directory? _cacheDir;

  LinDoUserPreferences _preferences = const LinDoUserPreferences();
  AuthenticatedUserProfile? _savedUser;
  String _savedCsrfToken = '';
  String _savedCookies = '';

  final Map<int, ReadHistoryEntry> _historyByTopicId = <int, ReadHistoryEntry>{};
  final List<ReadHistoryEntry> _orderedHistory = <ReadHistoryEntry>[];
  final Map<String, CloudDraftItem> _localDraftsByKey = <String, CloudDraftItem>{};

  List<CategoryInfo> _cachedCategories = const <CategoryInfo>[];
  Map<String, dynamic>? _cachedEmojisJson;

  LinDoLocalStore({Directory? stateDirOverride})
      : _stateDirOverride = stateDirOverride;

  Directory? get stateDirectory => _stateDir;

  LinDoUserPreferences get preferences => _preferences;

  AuthenticatedUserProfile? get savedUser => _savedUser;

  String get savedCsrfToken => _savedCsrfToken;

  String get savedCookies => _savedCookies;

  List<ReadHistoryEntry> get readHistory =>
      List<ReadHistoryEntry>.unmodifiable(_orderedHistory);

  List<CloudDraftItem> get localDrafts =>
      List<CloudDraftItem>.unmodifiable(_localDraftsByKey.values);

  List<CategoryInfo> get cachedCategories =>
      List<CategoryInfo>.unmodifiable(_cachedCategories);

  Map<String, dynamic>? get cachedEmojisJson => _cachedEmojisJson == null
      ? null
      : Map<String, dynamic>.unmodifiable(_cachedEmojisJson!);

  Future<void> initialize({Directory? stateDir}) async {
    final resolvedDir = stateDir ??
        _stateDirOverride ??
        await LinDoPlatformBridge.instance.resolveStateDirectory();
    if (!resolvedDir.existsSync()) {
      resolvedDir.createSync(recursive: true);
    }
    final cacheDir = Directory('${resolvedDir.path}${Platform.pathSeparator}cache');
    if (!cacheDir.existsSync()) {
      cacheDir.createSync(recursive: true);
    }
    _stateDir = resolvedDir;
    _cacheDir = cacheDir;

    await Future.wait(<Future<void>>[
      _loadPreferencesFromDisk(),
      _loadSessionFromDisk(),
      _loadHistoryFromDisk(),
      _loadDraftsFromDisk(),
      _loadCategoriesCacheFromDisk(),
      _loadEmojisCacheFromDisk(),
    ]);
    notifyListeners();
  }

  Future<void> updatePreferences(LinDoUserPreferences next) async {
    _preferences = next;
    await _writeJsonFile(_fileInState(_prefsFileName), next.toJson());
    notifyListeners();
  }

  Future<void> saveSession({
    required AuthenticatedUserProfile? user,
    String csrfToken = '',
    String rawCookies = '',
  }) async {
    _savedUser = user;
    _savedCsrfToken = csrfToken.trim();
    _savedCookies = rawCookies.trim();

    final payload = <String, dynamic>{
      'user': user?.toJson(),
      'csrfToken': _savedCsrfToken,
      'rawCookies': _savedCookies,
      'savedAtMs': DateTime.now().millisecondsSinceEpoch,
    };
    await _writeJsonFile(_fileInState(_sessionFileName), payload);
    notifyListeners();
  }

  Future<void> clearSession() async {
    _savedUser = null;
    _savedCsrfToken = '';
    _savedCookies = '';
    await _deleteFileQuietly(_fileInState(_sessionFileName));
    notifyListeners();
  }

  int? getReadFloor(int topicId) => _historyByTopicId[topicId]?.lastReadPostNumber;

  Future<void> recordTopicVisit({
    required int topicId,
    required String title,
    required String categoryName,
    String categoryColorHex = '0088CC',
    required int lastReadPostNumber,
    required int postsCount,
  }) async {
    if (topicId <= 0) return;

    final existing = _historyByTopicId[topicId];
    final effectiveFloor = math.max(
      math.max(1, lastReadPostNumber),
      existing?.lastReadPostNumber ?? 1,
    );
    final effectivePostsCount = math.max(
      math.max(effectiveFloor, postsCount),
      existing?.postsCount ?? 1,
    );
    final cleanTitle = title.trim().isNotEmpty
        ? title.trim()
        : (existing?.title ?? '');
    final cleanCategory = categoryName.trim().isNotEmpty
        ? categoryName.trim()
        : (existing?.categoryName ?? '');
    final cleanColor = categoryColorHex.trim().replaceFirst('#', '');

    final updated = ReadHistoryEntry(
      topicId: topicId,
      title: cleanTitle,
      categoryName: cleanCategory,
      categoryColorHex: cleanColor.isEmpty ? '0088CC' : cleanColor,
      lastReadPostNumber: effectiveFloor,
      postsCount: effectivePostsCount,
      visitedAtMs: DateTime.now().millisecondsSinceEpoch,
    );

    _historyByTopicId[topicId] = updated;
    _orderedHistory.removeWhere((e) => e.topicId == topicId);
    _orderedHistory.insert(0, updated);

    if (_orderedHistory.length > _maxReadHistoryItems) {
      final evicted = _orderedHistory.sublist(_maxReadHistoryItems);
      _orderedHistory.removeRange(_maxReadHistoryItems, _orderedHistory.length);
      for (final item in evicted) {
        _historyByTopicId.remove(item.topicId);
      }
    }

    await _persistHistoryToDisk();
    notifyListeners();
  }

  Future<void> clearReadHistory() async {
    _historyByTopicId.clear();
    _orderedHistory.clear();
    await _deleteFileQuietly(_fileInState(_historyFileName));
    notifyListeners();
  }

  CloudDraftItem? getLocalDraft(String draftKey) =>
      _localDraftsByKey[draftKey.trim()];

  Future<void> saveLocalDraft(CloudDraftItem draft) async {
    final key = draft.draftKey.trim();
    if (key.isEmpty) return;

    _localDraftsByKey[key] = CloudDraftItem(
      draftKey: key,
      sequence: draft.sequence,
      action: draft.action,
      topicId: draft.topicId,
      categoryId: draft.categoryId,
      replyToPostNumber: draft.replyToPostNumber,
      editPostId: draft.editPostId,
      title: draft.title,
      raw: draft.raw,
      tags: List<String>.unmodifiable(draft.tags),
      recipients: List<String>.unmodifiable(draft.recipients),
      updatedAtText: draft.updatedAtText.isEmpty
          ? DateTime.now().toUtc().toIso8601String()
          : draft.updatedAtText,
      isLocalOnly: draft.isLocalOnly,
    );

    await _persistDraftsToDisk();
    notifyListeners();
  }

  Future<void> deleteLocalDraft(String draftKey) async {
    final key = draftKey.trim();
    if (key.isEmpty) return;
    final removed = _localDraftsByKey.remove(key);
    if (removed == null) return;
    await _persistDraftsToDisk();
    notifyListeners();
  }

  Future<void> saveCachedCategories(List<CategoryInfo> items) async {
    _cachedCategories = List<CategoryInfo>.unmodifiable(items);
    final payload = <String, dynamic>{
      'categories': items.map(_categoryToJson).toList(growable: false),
      'updatedAtMs': DateTime.now().millisecondsSinceEpoch,
    };
    await _writeJsonFile(_fileInCache(_categoriesCacheFileName), payload);
    notifyListeners();
  }

  Future<void> saveCachedEmojisJson(Map<String, dynamic> raw) async {
    _cachedEmojisJson = Map<String, dynamic>.from(raw);
    await _writeJsonFile(_fileInCache(_emojisCacheFileName), _cachedEmojisJson!);
    notifyListeners();
  }

  Future<void> clearContentCache() async {
    _cachedCategories = const <CategoryInfo>[];
    _cachedEmojisJson = null;
    _historyByTopicId.clear();
    _orderedHistory.clear();

    await _deleteFileQuietly(_fileInState(_historyFileName));

    final cacheDir = _cacheDir;
    if (cacheDir != null && cacheDir.existsSync()) {
      try {
        for (final entity in cacheDir.listSync(followLinks: false)) {
          try {
            entity.deleteSync(recursive: true);
          } catch (_) {}
        }
      } catch (_) {}
    }

    notifyListeners();
  }

  File? _fileInState(String name) {
    final dir = _stateDir;
    if (dir == null) return null;
    return File('${dir.path}${Platform.pathSeparator}$name');
  }

  File? _fileInCache(String name) {
    final dir = _cacheDir;
    if (dir == null) return null;
    return File('${dir.path}${Platform.pathSeparator}$name');
  }

  Future<void> _loadPreferencesFromDisk() async {
    final map = await _readJsonMap(_fileInState(_prefsFileName));
    if (map != null) {
      _preferences = LinDoUserPreferences.fromJson(map);
    }
  }

  Future<void> _loadSessionFromDisk() async {
    final map = await _readJsonMap(_fileInState(_sessionFileName));
    if (map == null) return;
    final userRaw = map['user'];
    if (userRaw is Map<String, dynamic>) {
      final parsed = AuthenticatedUserProfile.fromJson(userRaw);
      _savedUser = parsed.username.trim().isEmpty ? null : parsed;
    } else if (userRaw is Map) {
      final parsed = AuthenticatedUserProfile.fromJson(
        userRaw.map((k, v) => MapEntry(k.toString(), v)),
      );
      _savedUser = parsed.username.trim().isEmpty ? null : parsed;
    } else {
      _savedUser = null;
    }
    _savedCsrfToken = (map['csrfToken'] as String?)?.trim() ?? '';
    _savedCookies = (map['rawCookies'] as String?)?.trim() ?? '';
  }

  Future<void> _loadHistoryFromDisk() async {
    final map = await _readJsonMap(_fileInState(_historyFileName));
    if (map == null) return;
    final itemsRaw = map['items'];
    if (itemsRaw is! List) return;

    _historyByTopicId.clear();
    _orderedHistory.clear();
    for (final raw in itemsRaw) {
      if (raw is! Map) continue;
      final entry = ReadHistoryEntry.fromJson(
        raw.map((k, v) => MapEntry(k.toString(), v)),
      );
      if (entry.topicId <= 0 || _historyByTopicId.containsKey(entry.topicId)) {
        continue;
      }
      _historyByTopicId[entry.topicId] = entry;
      _orderedHistory.add(entry);
    }
    _orderedHistory.sort((a, b) => b.visitedAtMs.compareTo(a.visitedAtMs));
  }

  Future<void> _persistHistoryToDisk() async {
    await _writeJsonFile(
      _fileInState(_historyFileName),
      <String, dynamic>{
        'items': _orderedHistory.map((e) => e.toJson()).toList(growable: false),
      },
    );
  }

  Future<void> _loadDraftsFromDisk() async {
    final map = await _readJsonMap(_fileInState(_draftsFileName));
    if (map == null) return;
    final itemsRaw = map['drafts'];
    if (itemsRaw is! List) return;

    _localDraftsByKey.clear();
    for (final raw in itemsRaw) {
      if (raw is! Map) continue;
      final draft = _draftFromJson(raw.map((k, v) => MapEntry(k.toString(), v)));
      if (draft.draftKey.isNotEmpty) {
        _localDraftsByKey[draft.draftKey] = draft;
      }
    }
  }

  Future<void> _persistDraftsToDisk() async {
    await _writeJsonFile(
      _fileInState(_draftsFileName),
      <String, dynamic>{
        'drafts': _localDraftsByKey.values.map(_draftToJson).toList(growable: false),
      },
    );
  }

  Future<void> _loadCategoriesCacheFromDisk() async {
    final map = await _readJsonMap(_fileInCache(_categoriesCacheFileName));
    if (map == null) return;
    final rawList = map['categories'];
    if (rawList is! List) return;

    final loaded = <CategoryInfo>[];
    for (final item in rawList) {
      if (item is! Map) continue;
      final parsed = _categoryFromJson(item.map((k, v) => MapEntry(k.toString(), v)));
      if (parsed.id > 0 && parsed.name.isNotEmpty) {
        loaded.add(parsed);
      }
    }
    _cachedCategories = List<CategoryInfo>.unmodifiable(loaded);
  }

  Future<void> _loadEmojisCacheFromDisk() async {
    final map = await _readJsonMap(_fileInCache(_emojisCacheFileName));
    if (map != null && map.isNotEmpty) {
      _cachedEmojisJson = map;
    }
  }

  static Future<Map<String, dynamic>?> _readJsonMap(File? file) async {
    if (file == null) return null;
    try {
      if (!file.existsSync()) return null;
      final content = file.readAsStringSync();
      if (content.trim().isEmpty) return null;
      final decoded = jsonDecode(content);
      if (decoded is Map<String, dynamic>) return decoded;
      if (decoded is Map) {
        return decoded.map((k, v) => MapEntry(k.toString(), v));
      }
    } catch (_) {}
    return null;
  }

  static Future<void> _writeJsonFile(
    File? file,
    Map<String, dynamic> payload,
  ) async {
    if (file == null) return;
    try {
      if (!file.parent.existsSync()) {
        file.parent.createSync(recursive: true);
      }
      final tmpFile = File('${file.path}.tmp');
      tmpFile.writeAsStringSync(jsonEncode(payload), flush: true);
      tmpFile.renameSync(file.path);
    } catch (_) {
      try {
        file.writeAsStringSync(jsonEncode(payload), flush: true);
      } catch (_) {}
    }
  }

  static Future<void> _deleteFileQuietly(File? file) async {
    if (file == null) return;
    try {
      if (file.existsSync()) {
        file.deleteSync();
      }
    } catch (_) {}
  }

  static Map<String, dynamic> _categoryToJson(CategoryInfo c) => <String, dynamic>{
        'id': c.id,
        'name': c.name,
        'slug': c.slug,
        'colorHex': c.colorHex,
        'textColorHex': c.textColorHex,
        'descriptionText': c.descriptionText,
        'topicCount': c.topicCount,
        'parentCategoryId': c.parentCategoryId,
        'minimumTrustLevel': c.minimumTrustLevel,
        'readRestricted': c.readRestricted,
        'defaultTemplate': c.defaultTemplate,
        'allowedTags': c.allowedTags,
        'requiredTagGroupNames': c.requiredTagGroupNames,
      };

  static CategoryInfo _categoryFromJson(Map<String, dynamic> json) => CategoryInfo(
        id: (json['id'] as num?)?.toInt() ?? 0,
        name: (json['name'] as String?) ?? '',
        slug: (json['slug'] as String?) ?? '',
        colorHex: (json['colorHex'] as String?) ?? '0088CC',
        textColorHex: (json['textColorHex'] as String?) ?? 'FFFFFF',
        descriptionText: (json['descriptionText'] as String?) ?? '',
        topicCount: (json['topicCount'] as num?)?.toInt() ?? 0,
        parentCategoryId: (json['parentCategoryId'] as num?)?.toInt(),
        minimumTrustLevel: (json['minimumTrustLevel'] as num?)?.toInt() ?? 0,
        readRestricted: (json['readRestricted'] as bool?) ?? false,
        defaultTemplate: (json['defaultTemplate'] as String?) ?? '',
        allowedTags: _readStringList(json['allowedTags']),
        requiredTagGroupNames: _readStringList(json['requiredTagGroupNames']),
      );

  static Map<String, dynamic> _draftToJson(CloudDraftItem d) => <String, dynamic>{
        'draftKey': d.draftKey,
        'sequence': d.sequence,
        'action': d.action,
        'topicId': d.topicId,
        'categoryId': d.categoryId,
        'replyToPostNumber': d.replyToPostNumber,
        'editPostId': d.editPostId,
        'title': d.title,
        'raw': d.raw,
        'tags': d.tags,
        'recipients': d.recipients,
        'updatedAtText': d.updatedAtText,
        'isLocalOnly': d.isLocalOnly,
      };

  static CloudDraftItem _draftFromJson(Map<String, dynamic> json) => CloudDraftItem(
        draftKey: (json['draftKey'] as String?)?.trim() ?? '',
        sequence: (json['sequence'] as num?)?.toInt() ?? 0,
        action: (json['action'] as String?) ?? 'reply',
        topicId: (json['topicId'] as num?)?.toInt(),
        categoryId: (json['categoryId'] as num?)?.toInt(),
        replyToPostNumber: (json['replyToPostNumber'] as num?)?.toInt(),
        editPostId: (json['editPostId'] as num?)?.toInt(),
        title: (json['title'] as String?) ?? '',
        raw: (json['raw'] as String?) ?? '',
        tags: _readStringList(json['tags']),
        recipients: _readStringList(json['recipients']),
        updatedAtText: (json['updatedAtText'] as String?) ?? '',
        isLocalOnly: (json['isLocalOnly'] as bool?) ?? false,
      );

  static List<String> _readStringList(Object? raw) {
    if (raw is! List) return const <String>[];
    return raw
        .map((e) => e?.toString().trim() ?? '')
        .where((s) => s.isNotEmpty)
        .toList(growable: false);
  }
}
