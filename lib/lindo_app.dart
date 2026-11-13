import 'dart:async';
import 'dart:ui';

import 'package:flutter/cupertino.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import 'core/copy/lindo_copy.dart';
import 'core/diagnostics/lindo_diagnostic_logger.dart';
import 'core/models/lindo_models.dart';
import 'core/network/linux_do_repository.dart';
import 'core/platform/lindo_platform_channels.dart';
import 'core/site/linux_do_site_rules.dart';
import 'core/storage/lindo_local_store.dart';
import 'ui/components/composer_workbench.dart';
import 'ui/components/lindo_brand_widgets.dart';
import 'ui/components/lindo_icons.dart';
import 'ui/components/web_auth_modal.dart';
import 'ui/screens/discover_pane.dart';
import 'ui/screens/home_feed_pane.dart';
import 'ui/screens/messages_pane.dart';
import 'ui/screens/profile_pane.dart';
import 'ui/screens/topic_reader_pane.dart';
import 'ui/sheets/user_collections_sheets.dart';
import 'ui/theme/lindo_theme.dart';

typedef LinDoRootApp = LinDoApp;

/// 跨端 120Hz 高帧率滚动行为：
/// 同时支持触控、鼠标拖拽、触控板与手写笔，桌面与移动端均丝滑顺畅。
class LinDoScrollBehavior extends MaterialScrollBehavior {
  const LinDoScrollBehavior();

  @override
  Set<PointerDeviceKind> get dragDevices => const <PointerDeviceKind>{
        PointerDeviceKind.touch,
        PointerDeviceKind.mouse,
        PointerDeviceKind.trackpad,
        PointerDeviceKind.stylus,
        PointerDeviceKind.invertedStylus,
        PointerDeviceKind.unknown,
      };

  @override
  ScrollPhysics getScrollPhysics(BuildContext context) {
    switch (getPlatform(context)) {
      case TargetPlatform.iOS:
      case TargetPlatform.macOS:
      case TargetPlatform.android:
        return const BouncingScrollPhysics(
          parent: AlwaysScrollableScrollPhysics(),
        );
      case TargetPlatform.windows:
      case TargetPlatform.linux:
      case TargetPlatform.fuchsia:
        return const ClampingScrollPhysics(
          parent: AlwaysScrollableScrollPhysics(),
        );
    }
  }
}

class LinDoApp extends StatefulWidget {
  final LinDoLocalStore? store;
  final LinuxDoRepository? repository;
  final bool autoBootstrap;

  const LinDoApp({
    super.key,
    this.store,
    this.repository,
    this.autoBootstrap = true,
  });

  @override
  State<LinDoApp> createState() => _LinDoAppState();
}

class _LinDoAppState extends State<LinDoApp> with WidgetsBindingObserver {
  final GlobalKey<NavigatorState> _navigatorKey = GlobalKey<NavigatorState>();

  late final LinDoLocalStore _store;
  late final bool _ownsStore;
  late final LinuxDoRepository _repository;
  late final bool _ownsRepository;

  StreamSubscription<double>? _macInsetSub;
  StreamSubscription<DesktopTrayCommand>? _trayCommandSub;

  double _macTitleBarInset = LinDoPlatformBridge.instance.currentMacTitleBarInset;
  bool? _lastSyncedWindowDark;
  int? _lastSyncedTrayUnread;
  String? _lastSyncedTrayUsername;

  MainTab _activeTab = MainTab.HOME;

  // Topic selection state (persisted for desktop split view)
  int? _selectedTopicId;
  int? _selectedPostNumber;
  String? _selectedTopicTitle;

  // Secondary split-reader state when opening a topic from Discover / Messages / Profile on desktop
  int? _auxSplitTopicId;
  int? _auxSplitPostNumber;
  String? _auxSplitTopicTitle;

  // Home feed category / tag filters
  int? _filterCategoryId;
  String? _filterCategorySlug;
  String? _filterCategoryName;
  String? _filterTag;

  // Auth & modal transition state
  bool _loginModalOpen = false;
  bool _siteVerifyModalOpen = false;
  bool _showingLoginTransition = false;
  String _transitionUsername = '';
  Timer? _loginTransitionTimer;

  // Lightweight floating toast state
  String? _toastMessage;
  Timer? _toastTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);

    if (widget.store != null) {
      _store = widget.store!;
      _ownsStore = false;
    } else {
      _store = LinDoLocalStore();
      _ownsStore = true;
    }

    if (widget.repository != null) {
      _repository = widget.repository!;
      _ownsRepository = false;
    } else {
      _repository = LinuxDoRepository(store: _store);
      _ownsRepository = true;
    }

    _store.addListener(_onStoreOrRepoChanged);
    _repository.addListener(_onStoreOrRepoChanged);

    _macInsetSub = LinDoPlatformBridge.instance.macTitleBarInsets.listen((inset) {
      if (mounted && (inset - _macTitleBarInset).abs() > 0.1) {
        setState(() => _macTitleBarInset = inset);
      }
    });

    _trayCommandSub =
        LinDoPlatformBridge.instance.trayCommands.listen(_handleTrayCommand);

    if (widget.autoBootstrap) {
      unawaited(_initializeAndBootstrap());
    }
  }

  Future<void> _initializeAndBootstrap() async {
    try {
      if (_ownsStore && _store.stateDirectory == null) {
        await _store.initialize();
      }
      if (!_repository.isBootstrapped) {
        await _repository.bootstrap();
      }
      _repository.startLiveNotificationSync();
      unawaited(_warmUpSiteMetadata());
    } catch (err, st) {
      LinDoDiagnosticLogger.instance.error(
        DiagnosticCategory.SYSTEM,
        'Bootstrap',
        'Initial app bootstrap encountered an error',
        error: err,
        stackTrace: st,
      );
    }
  }

  Future<void> _warmUpSiteMetadata() async {
    try {
      await _repository.fetchCategories();
    } catch (_) {}
    try {
      await _repository.syncCustomEmojis();
    } catch (_) {}
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_repository.onAppResumed());
      if (widget.autoBootstrap) {
        _repository.startLiveNotificationSync();
      }
    }
  }

  @override
  void didChangePlatformBrightness() {
    if (mounted) {
      setState(() {});
    }
  }

  void _onStoreOrRepoChanged() {
    _syncTrayIfNeeded();
    if (mounted) {
      setState(() {});
    }
  }

  void _syncTrayIfNeeded() {
    final unread = _repository.totalUnreadBadgeCount;
    final username = _repository.currentUser?.username;
    if (_lastSyncedTrayUnread != unread ||
        _lastSyncedTrayUsername != username) {
      _lastSyncedTrayUnread = unread;
      _lastSyncedTrayUsername = username;
      unawaited(
        LinDoPlatformBridge.instance.updateTrayState(
          unreadCount: unread,
          username: username,
        ),
      );
    }
  }

  void _syncNativeWindowThemeIfNeeded(bool isDark) {
    if (_lastSyncedWindowDark == isDark) return;
    _lastSyncedWindowDark = isDark;
    unawaited(LinDoPlatformBridge.instance.syncWindowTheme(isDark: isDark));
  }

  void _handleTrayCommand(DesktopTrayCommand command) {
    if (!mounted) return;
    switch (command) {
      case DesktopTrayCommand.OPEN_MESSAGES:
        _popMobileSecondaryRoutes();
        setState(() {
          _activeTab = MainTab.MESSAGES;
        });
        unawaited(_repository.refreshCurrentSessionAndUnread(silent: true));
        break;
      case DesktopTrayCommand.OPEN_PUBLISH:
        unawaited(_openPublishComposer());
        break;
    }
  }

  void _popMobileSecondaryRoutes() {
    final nav = _navigatorKey.currentState;
    if (nav != null && nav.canPop()) {
      nav.popUntil((route) => route.isFirst);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _loginTransitionTimer?.cancel();
    _toastTimer?.cancel();
    _macInsetSub?.cancel();
    _trayCommandSub?.cancel();
    _store.removeListener(_onStoreOrRepoChanged);
    _repository.removeListener(_onStoreOrRepoChanged);
    if (_ownsRepository) {
      _repository.dispose();
    } else {
      _repository.stopLiveNotificationSync();
    }
    if (_ownsStore) {
      _store.dispose();
    }
    super.dispose();
  }

  // ---------------------------------------------------------------------------
  // Toast Helper
  // ---------------------------------------------------------------------------

  void _showToast(String message) {
    final clean = message.trim();
    if (clean.isEmpty || !mounted) return;
    _toastTimer?.cancel();
    setState(() => _toastMessage = clean);
    _toastTimer = Timer(const Duration(milliseconds: 2400), () {
      if (mounted) {
        setState(() => _toastMessage = null);
      }
    });
  }

  // ---------------------------------------------------------------------------
  // Authentication, Site Verification & In-App Browser (ZERO Cookie Paste UI!)
  // ---------------------------------------------------------------------------

  AuthenticatedUserProfile? _parseModalUserPayload(
    Map<String, dynamic>? payload,
  ) {
    if (payload == null || payload.isEmpty) return null;
    if (payload.containsKey('userId') && payload['username'] is String) {
      final parsed = AuthenticatedUserProfile.fromJson(payload);
      return parsed.username.trim().isEmpty ? null : parsed;
    }
    final rawUsername = (payload['username'] as String?)?.trim() ?? '';
    if (rawUsername.isEmpty) return null;
    final rawName = (payload['name'] as String?)?.trim() ?? '';
    final rawAnimated = (payload['animated_avatar'] as String?)?.trim();
    return AuthenticatedUserProfile(
      userId: (payload['id'] as num?)?.toInt() ?? 0,
      username: rawUsername,
      displayName: rawName.isNotEmpty ? rawName : rawUsername,
      avatarTemplate: (payload['avatar_template'] as String?) ?? '',
      animatedAvatar:
          (rawAnimated != null && rawAnimated.isNotEmpty) ? rawAnimated : null,
      userTitle: (payload['title'] as String?) ?? '',
      trustLevel: (payload['trust_level'] as num?)?.toInt() ?? 1,
    );
  }

  Future<void> _handleContinueAsGuest() async {
    await _store.updatePreferences(
      _store.preferences.copyWith(hasCompletedWelcome: true),
    );
  }

  Future<void> _handleStartWebLogin() async {
    if (_loginModalOpen) return;
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;

    _loginModalOpen = true;
    try {
      final result = await showLinDoWebAuthModal(
        navContext,
        initialUrl: '$kLinuxDoBaseUrl/login',
        mode: WebAuthModalMode.login,
      );
      if (!mounted || result == null || !result.authenticated) return;

      final hintedUser = _parseModalUserPayload(result.userPayload);
      final resolvedProfile = await _repository.completeWebModalAuthentication(
        csrfToken: result.csrfToken,
        rawCookies: result.rawCookies,
        user: hintedUser,
      );

      await _store.updatePreferences(
        _store.preferences.copyWith(hasCompletedWelcome: true),
      );
      _repository.startLiveNotificationSync();
      unawaited(_warmUpSiteMetadata());

      if (!mounted) return;
      _loginTransitionTimer?.cancel();
      setState(() {
        _transitionUsername =
            resolvedProfile?.username ?? hintedUser?.username ?? '';
        _showingLoginTransition = true;
      });
      _loginTransitionTimer = Timer(const Duration(milliseconds: 1150), () {
        if (mounted) {
          setState(() => _showingLoginTransition = false);
        }
      });
    } finally {
      _loginModalOpen = false;
    }
  }

  Future<void> _handleStartSiteVerify() async {
    if (_siteVerifyModalOpen) return;
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;

    _siteVerifyModalOpen = true;
    try {
      final result = await showLinDoWebAuthModal(
        navContext,
        initialUrl: '$kLinuxDoBaseUrl/',
        mode: WebAuthModalMode.siteVerify,
      );
      if (!mounted || result == null || !result.siteVerified) return;

      await _repository.onSiteVerificationPassed(
        csrfToken: result.csrfToken,
        rawCookies: result.rawCookies,
      );
      unawaited(_repository.refreshCurrentSessionAndUnread(silent: true));
      _showToast(LinDoCommonCopy.COMPLETED.text);
    } finally {
      _siteVerifyModalOpen = false;
    }
  }

  void _openInAppBrowserUrl(String url) {
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;
    final resolved = resolveDiscourseUrl(url);
    if (resolved.isEmpty) return;

    final internalTopic = parseInternalTopicUrl(resolved);
    if (internalTopic != null) {
      _openTopic(
        internalTopic.topicId,
        postNumber: internalTopic.postNumber,
      );
      return;
    }

    unawaited(
      showLinDoWebAuthModal(
        navContext,
        initialUrl: resolved,
        mode: WebAuthModalMode.inAppBrowser,
      ),
    );
  }

  void _openGuestLoginPrompt({
    GuestGateScenario scenario = GuestGateScenario.DEFAULT,
    String? customTitle,
    String? customSubtitle,
  }) {
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;
    unawaited(
      showGuestLoginGuideSheet(
        navContext,
        scenario: scenario,
        customTitle: customTitle,
        customSubtitle: customSubtitle,
        onStartWebLogin: _handleStartWebLogin,
      ),
    );
  }

  // ---------------------------------------------------------------------------
  // Navigation & Publishing Actions
  // ---------------------------------------------------------------------------

  Future<void> _openPublishComposer() async {
    final navContext = _navigatorKey.currentContext;
    if (navContext == null) return;

    if (!_repository.isAuthorized) {
      await showGuestLoginGuideSheet(
        navContext,
        scenario: GuestGateScenario.PUBLISH_TOPIC,
        onStartWebLogin: _handleStartWebLogin,
      );
      return;
    }

    final outcome = await showLinDoComposerSheet(
      navContext,
      repository: _repository,
      store: _store,
      action: ComposerAction.NEW_TOPIC,
      initialCategoryId: _filterCategoryId,
      initialTags: (_filterTag != null && _filterTag!.isNotEmpty)
          ? <String>[_filterTag!]
          : null,
      onStartSiteVerify: _handleStartSiteVerify,
    );

    if (!mounted || outcome == null || !outcome.success) return;
    if (outcome.topicId != null && outcome.topicId! > 0) {
      _openTopic(
        outcome.topicId!,
        postNumber: outcome.post?.postNumber,
        title: outcome.title,
      );
    }
  }

  void _selectMainTab(MainTab tab) {
    if (_activeTab == tab) {
      if (tab == MainTab.MESSAGES && _repository.isAuthorized) {
        unawaited(_repository.refreshCurrentSessionAndUnread(silent: true));
      }
      return;
    }
    setState(() {
      _activeTab = tab;
      if (tab == MainTab.HOME) {
        _auxSplitTopicId = null;
      }
    });
    if (tab == MainTab.MESSAGES && _repository.isAuthorized) {
      unawaited(_repository.refreshCurrentSessionAndUnread(silent: true));
    }
  }

  void _handleSelectCategory(CategoryInfo? category) {
    _popMobileSecondaryRoutes();
    setState(() {
      _filterCategoryId = category?.id;
      _filterCategorySlug = category?.slug;
      _filterCategoryName = category?.name;
      if (category != null) {
        _activeTab = MainTab.HOME;
        _auxSplitTopicId = null;
      }
    });
  }

  void _handleSelectTag(String? tag) {
    final clean = tag?.trim();
    _popMobileSecondaryRoutes();
    setState(() {
      _filterTag = (clean != null && clean.isNotEmpty) ? clean : null;
      if (_filterTag != null) {
        _activeTab = MainTab.HOME;
        _auxSplitTopicId = null;
      }
    });
  }

  void _openTopic(
    int topicId, {
    int? postNumber,
    String? title,
  }) {
    if (topicId <= 0) return;
    final navState = _navigatorKey.currentState;
    final navContext = _navigatorKey.currentContext;
    if (navContext == null || navState == null) return;

    final size = MediaQuery.sizeOf(navContext);
    final isWide = shouldUseWideSplitLayout(size.width, size.height);

    setState(() {
      _selectedTopicId = topicId;
      _selectedPostNumber = postNumber;
      _selectedTopicTitle = title;
      if (isWide && _activeTab != MainTab.HOME) {
        _auxSplitTopicId = topicId;
        _auxSplitPostNumber = postNumber;
        _auxSplitTopicTitle = title;
      }
    });

    if (!isWide) {
      navState.push<void>(
        CupertinoPageRoute<void>(
          builder: (routeContext) => Scaffold(
            backgroundColor: LinDoThemeScope.paletteOf(routeContext).canvas,
            body: SafeArea(
              bottom: false,
              child: TopicReaderPane(
                key: ValueKey<String>(
                  'mobile_reader_${topicId}_${postNumber ?? 0}',
                ),
                topicId: topicId,
                initialPostNumber: postNumber,
                initialTitle: title,
                repository: _repository,
                store: _store,
                showBackButton: true,
                onClose: () => Navigator.of(routeContext).maybePop(),
                onOpenTopic: _openTopic,
                onSelectTag: (t) => _handleSelectTag(t),
                onStartWebLogin: _handleStartWebLogin,
                onStartSiteVerify: _handleStartSiteVerify,
                onShowToast: _showToast,
              ),
            ),
          ),
        ),
      );
    }
  }

  Future<void> _toggleQuickTheme(bool currentIsDark) async {
    await _store.updatePreferences(
      _store.preferences.copyWith(isDarkOverride: !currentIsDark),
    );
  }

  // ---------------------------------------------------------------------------
  // Root Build
  // ---------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final prefs = _store.preferences;
    final platformDark =
        PlatformDispatcher.instance.platformBrightness == Brightness.dark;
    final isDark = prefs.isDarkOverride ?? platformDark;
    _syncNativeWindowThemeIfNeeded(isDark);

    final palette = buildLinDoPalette(
      isDark: isDark,
      option: prefs.brandTheme,
    );
    final materialTheme = buildLinDoMaterialTheme(palette);

    return LinDoThemeScope(
      palette: palette,
      appIcon: prefs.appIcon,
      fontScale: prefs.fontScale,
      enableAnimations: prefs.enableAnimations,
      macTitleBarInset: _macTitleBarInset,
      onOpenInAppWebUrl: _openInAppBrowserUrl,
      onRequestGuestLogin: (title, subtitle) => _openGuestLoginPrompt(
        customTitle: title,
        customSubtitle: subtitle,
      ),
      child: MaterialApp(
        navigatorKey: _navigatorKey,
        debugShowCheckedModeBanner: false,
        title: LinDoCommonCopy.APP_NAME.text,
        theme: materialTheme,
        scrollBehavior: const LinDoScrollBehavior(),
        builder: (context, child) {
          return Stack(
            children: [
              ?child,
              if (_showingLoginTransition)
                Positioned.fill(
                  child: LinDoMascotLoginTransitionView(
                    username: _transitionUsername,
                  ),
                ),
              if (_toastMessage != null)
                _FloatingToastOverlay(message: _toastMessage!),
            ],
          );
        },
        home: _buildHomeOrWelcome(palette, prefs, isDark),
      ),
    );
  }

  Widget _buildHomeOrWelcome(
    LinDoThemePalette palette,
    LinDoUserPreferences prefs,
    bool isDark,
  ) {
    final shouldShowWelcome =
        !prefs.hasCompletedWelcome && !_repository.isAuthorized;
    if (shouldShowWelcome) {
      return LinDoWelcomeScreen(
        onStartWebLogin: () => unawaited(_handleStartWebLogin()),
        onContinueAsGuest: () => unawaited(_handleContinueAsGuest()),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final isWide = shouldUseWideSplitLayout(
          constraints.maxWidth,
          constraints.maxHeight,
        );
        if (isWide) {
          return _buildDesktopWideShell(
            context,
            palette: palette,
            prefs: prefs,
            isDark: isDark,
            totalWidth: constraints.maxWidth,
          );
        }
        return _buildMobileShell(
          context,
          palette: palette,
          prefs: prefs,
        );
      },
    );
  }

  // ---------------------------------------------------------------------------
  // Desktop Wide Split Shell (macOS / Windows / Wide Tablet)
  // ---------------------------------------------------------------------------

  Widget _buildDesktopWideShell(
    BuildContext context, {
    required LinDoThemePalette palette,
    required LinDoUserPreferences prefs,
    required bool isDark,
    required double totalWidth,
  }) {
    final unreadCount = _repository.totalUnreadBadgeCount;
    final workspaceWidth = (totalWidth - 76.0).clamp(320.0, 4000.0);

    return Scaffold(
      backgroundColor: palette.canvas,
      body: Column(
        children: [
          if (_macTitleBarInset > 0)
            _MacTrafficLightTitleBar(
              height: _macTitleBarInset,
              palette: palette,
            ),
          Expanded(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                _DesktopSideRail(
                  palette: palette,
                  appIcon: prefs.appIcon,
                  activeTab: _activeTab,
                  unreadBadgeCount: unreadCount,
                  isDark: isDark,
                  onSelectTab: _selectMainTab,
                  onTapPublish: () => unawaited(_openPublishComposer()),
                  onToggleTheme: () => unawaited(_toggleQuickTheme(isDark)),
                ),
                VerticalDivider(
                  width: 1,
                  thickness: 1,
                  color: palette.hairline,
                ),
                Expanded(
                  child: _buildDesktopWorkspace(
                    context,
                    palette: palette,
                    workspaceWidth: workspaceWidth,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildDesktopWorkspace(
    BuildContext context, {
    required LinDoThemePalette palette,
    required double workspaceWidth,
  }) {
    if (_activeTab == MainTab.HOME) {
      final feedWidth = (workspaceWidth * 0.38).clamp(380.0, 440.0);
      return Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            width: feedWidth,
            child: HomeFeedPane(
              key: const PageStorageKey<String>('desktop_home_feed'),
              repository: _repository,
              store: _store,
              selectedTopicId: _selectedTopicId,
              filterCategoryId: _filterCategoryId,
              filterCategorySlug: _filterCategorySlug,
              filterCategoryName: _filterCategoryName,
              filterTag: _filterTag,
              onCategoryChanged: _handleSelectCategory,
              onTagChanged: _handleSelectTag,
              onOpenTopic: _openTopic,
              onStartWebLogin: _handleStartWebLogin,
              onStartSiteVerify: _handleStartSiteVerify,
              onShowToast: _showToast,
              isCompactDesktopColumn: true,
            ),
          ),
          VerticalDivider(
            width: 1,
            thickness: 1,
            color: palette.hairline,
          ),
          Expanded(
            child: TopicReaderPane(
              key: ValueKey<String>(
                'desktop_home_reader_${_selectedTopicId ?? 0}_${_selectedPostNumber ?? 0}',
              ),
              topicId: _selectedTopicId,
              initialPostNumber: _selectedPostNumber,
              initialTitle: _selectedTopicTitle,
              repository: _repository,
              store: _store,
              showBackButton: false,
              onOpenTopic: _openTopic,
              onSelectTag: (t) => _handleSelectTag(t),
              onStartWebLogin: _handleStartWebLogin,
              onStartSiteVerify: _handleStartSiteVerify,
              onShowToast: _showToast,
            ),
          ),
        ],
      );
    }

    final Widget primaryPane;
    switch (_activeTab) {
      case MainTab.DISCOVER:
        primaryPane = DiscoverPane(
          key: const PageStorageKey<String>('desktop_discover_pane'),
          repository: _repository,
          store: _store,
          onSelectCategory: (cat) => _handleSelectCategory(cat),
          onSelectTag: (tag) => _handleSelectTag(tag),
          onOpenTopic: _openTopic,
          onStartWebLogin: _handleStartWebLogin,
          onStartSiteVerify: _handleStartSiteVerify,
          onShowToast: _showToast,
        );
        break;
      case MainTab.MESSAGES:
        primaryPane = MessagesPane(
          key: const PageStorageKey<String>('desktop_messages_pane'),
          repository: _repository,
          store: _store,
          onOpenTopic: _openTopic,
          onStartWebLogin: _handleStartWebLogin,
          onStartSiteVerify: _handleStartSiteVerify,
          onShowToast: _showToast,
        );
        break;
      case MainTab.PROFILE:
        primaryPane = ProfilePane(
          key: const PageStorageKey<String>('desktop_profile_pane'),
          repository: _repository,
          store: _store,
          onOpenTopic: _openTopic,
          onStartWebLogin: _handleStartWebLogin,
          onStartSiteVerify: _handleStartSiteVerify,
          onShowToast: _showToast,
        );
        break;
      case MainTab.HOME:
        primaryPane = const SizedBox.shrink();
        break;
    }

    if (_auxSplitTopicId != null && workspaceWidth >= 780) {
      final leftWidth = (workspaceWidth * 0.42).clamp(380.0, 480.0);
      return Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(width: leftWidth, child: primaryPane),
          VerticalDivider(
            width: 1,
            thickness: 1,
            color: palette.hairline,
          ),
          Expanded(
            child: TopicReaderPane(
              key: ValueKey<String>(
                'desktop_aux_reader_${_auxSplitTopicId}_${_auxSplitPostNumber ?? 0}',
              ),
              topicId: _auxSplitTopicId,
              initialPostNumber: _auxSplitPostNumber,
              initialTitle: _auxSplitTopicTitle,
              repository: _repository,
              store: _store,
              showBackButton: false,
              onClose: () => setState(() => _auxSplitTopicId = null),
              onOpenTopic: _openTopic,
              onSelectTag: (t) => _handleSelectTag(t),
              onStartWebLogin: _handleStartWebLogin,
              onStartSiteVerify: _handleStartSiteVerify,
              onShowToast: _showToast,
            ),
          ),
        ],
      );
    }

    return primaryPane;
  }

  // ---------------------------------------------------------------------------
  // Mobile Shell (IndexedStack + Frosted Floating Pill Navigation Bar)
  // ---------------------------------------------------------------------------

  Widget _buildMobileShell(
    BuildContext context, {
    required LinDoThemePalette palette,
    required LinDoUserPreferences prefs,
  }) {
    final unreadCount = _repository.totalUnreadBadgeCount;
    final tabIndex = switch (_activeTab) {
      MainTab.HOME => 0,
      MainTab.DISCOVER => 1,
      MainTab.MESSAGES => 2,
      MainTab.PROFILE => 3,
    };

    return Scaffold(
      backgroundColor: palette.canvas,
      extendBody: true,
      body: Stack(
        children: [
          Positioned.fill(
            child: SafeArea(
              bottom: false,
              child: Padding(
                padding: EdgeInsets.only(top: _macTitleBarInset),
                child: IndexedStack(
                  index: tabIndex,
                  children: [
                    HomeFeedPane(
                      key: const PageStorageKey<String>('mobile_home_feed'),
                      repository: _repository,
                      store: _store,
                      selectedTopicId: null,
                      filterCategoryId: _filterCategoryId,
                      filterCategorySlug: _filterCategorySlug,
                      filterCategoryName: _filterCategoryName,
                      filterTag: _filterTag,
                      onCategoryChanged: _handleSelectCategory,
                      onTagChanged: _handleSelectTag,
                      onOpenTopic: _openTopic,
                      onStartWebLogin: _handleStartWebLogin,
                      onStartSiteVerify: _handleStartSiteVerify,
                      onShowToast: _showToast,
                      isCompactDesktopColumn: false,
                    ),
                    DiscoverPane(
                      key: const PageStorageKey<String>('mobile_discover_pane'),
                      repository: _repository,
                      store: _store,
                      onSelectCategory: (cat) => _handleSelectCategory(cat),
                      onSelectTag: (tag) => _handleSelectTag(tag),
                      onOpenTopic: _openTopic,
                      onStartWebLogin: _handleStartWebLogin,
                      onStartSiteVerify: _handleStartSiteVerify,
                      onShowToast: _showToast,
                    ),
                    MessagesPane(
                      key: const PageStorageKey<String>('mobile_messages_pane'),
                      repository: _repository,
                      store: _store,
                      onOpenTopic: _openTopic,
                      onStartWebLogin: _handleStartWebLogin,
                      onStartSiteVerify: _handleStartSiteVerify,
                      onShowToast: _showToast,
                    ),
                    ProfilePane(
                      key: const PageStorageKey<String>('mobile_profile_pane'),
                      repository: _repository,
                      store: _store,
                      onOpenTopic: _openTopic,
                      onStartWebLogin: _handleStartWebLogin,
                      onStartSiteVerify: _handleStartSiteVerify,
                      onShowToast: _showToast,
                    ),
                  ],
                ),
              ),
            ),
          ),
          Positioned(
            left: 16,
            right: 16,
            bottom: 12 + MediaQuery.paddingOf(context).bottom * 0.65,
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 430),
                child: _MobileFrostedPillNavBar(
                  palette: palette,
                  activeTab: _activeTab,
                  unreadBadgeCount: unreadCount,
                  onSelectTab: _selectMainTab,
                  onTapPublish: () => unawaited(_openPublishComposer()),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// =============================================================================
// macOS Traffic-Light Safe Title Bar Strip
// =============================================================================

class _MacTrafficLightTitleBar extends StatelessWidget {
  final double height;
  final LinDoThemePalette palette;

  const _MacTrafficLightTitleBar({
    required this.height,
    required this.palette,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      height: height,
      decoration: BoxDecoration(
        color: palette.navBarSurface,
        border: Border(
          bottom: BorderSide(color: palette.hairline, width: 0.75),
        ),
      ),
    );
  }
}

// =============================================================================
// Desktop Left Side Rail (76dp)
// =============================================================================

class _DesktopSideRail extends StatelessWidget {
  final LinDoThemePalette palette;
  final LinDoAppIcon appIcon;
  final MainTab activeTab;
  final int unreadBadgeCount;
  final bool isDark;
  final ValueChanged<MainTab> onSelectTab;
  final VoidCallback onTapPublish;
  final VoidCallback onToggleTheme;

  const _DesktopSideRail({
    required this.palette,
    required this.appIcon,
    required this.activeTab,
    required this.unreadBadgeCount,
    required this.isDark,
    required this.onSelectTab,
    required this.onTapPublish,
    required this.onToggleTheme,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);

    return Container(
      width: 76,
      color: palette.navBarSurface,
      padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 8),
      child: Column(
        children: [
          Tooltip(
            message: LinDoCommonCopy.APP_NAME.text,
            child: GestureDetector(
              onTap: () => onSelectTab(MainTab.HOME),
              child: LinDoGlassAppIcon(
                icon: appIcon,
                size: 38,
              ),
            ),
          ),
          const SizedBox(height: 20),
          _DesktopRailNavItem(
            palette: palette,
            scope: scope,
            icon: LinDoIcons.Home,
            label: MainTab.HOME.title,
            selected: activeTab == MainTab.HOME,
            onTap: () => onSelectTab(MainTab.HOME),
          ),
          const SizedBox(height: 6),
          _DesktopRailNavItem(
            palette: palette,
            scope: scope,
            icon: LinDoIcons.Compass,
            label: MainTab.DISCOVER.title,
            selected: activeTab == MainTab.DISCOVER,
            onTap: () => onSelectTab(MainTab.DISCOVER),
          ),
          const SizedBox(height: 10),
          Tooltip(
            message: LinDoFeedCopy.PUBLISH_NEW_TOPIC_DESC.text,
            child: Material(
              color: palette.publishBtnBg,
              borderRadius: BorderRadius.circular(14),
              child: InkWell(
                borderRadius: BorderRadius.circular(14),
                onTap: onTapPublish,
                child: SizedBox(
                  width: 52,
                  height: 40,
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        LinDoIcons.Plus,
                        size: 16,
                        color: palette.publishBtnContent,
                      ),
                      const SizedBox(width: 2),
                      Text(
                        LinDoCommonCopy.PUBLISH.text,
                        style: TextStyle(
                          color: palette.publishBtnContent,
                          fontSize: scope.sp(11.5),
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 10),
          _DesktopRailNavItem(
            palette: palette,
            scope: scope,
            icon: LinDoIcons.Bell,
            label: MainTab.MESSAGES.title,
            selected: activeTab == MainTab.MESSAGES,
            badgeCount: unreadBadgeCount,
            onTap: () => onSelectTab(MainTab.MESSAGES),
          ),
          const SizedBox(height: 6),
          _DesktopRailNavItem(
            palette: palette,
            scope: scope,
            icon: LinDoIcons.User,
            label: MainTab.PROFILE.title,
            selected: activeTab == MainTab.PROFILE,
            onTap: () => onSelectTab(MainTab.PROFILE),
          ),
          const Spacer(),
          Tooltip(
            message: isDark
                ? LinDoFeedCopy.SWITCH_TO_LIGHT_MODE_DESC.text
                : LinDoFeedCopy.SWITCH_TO_DARK_MODE_DESC.text,
            child: Material(
              color: palette.tonalPill.withValues(alpha: 0.72),
              borderRadius: BorderRadius.circular(13),
              child: InkWell(
                borderRadius: BorderRadius.circular(13),
                onTap: onToggleTheme,
                child: SizedBox(
                  width: 42,
                  height: 42,
                  child: Icon(
                    isDark ? LinDoIcons.Sun : LinDoIcons.Moon,
                    size: 19,
                    color: palette.inkSecondary,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _DesktopRailNavItem extends StatelessWidget {
  final LinDoThemePalette palette;
  final LinDoThemeScope scope;
  final IconData icon;
  final String label;
  final bool selected;
  final int badgeCount;
  final VoidCallback onTap;

  const _DesktopRailNavItem({
    required this.palette,
    required this.scope,
    required this.icon,
    required this.label,
    required this.selected,
    this.badgeCount = 0,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final fg = selected ? palette.activeChipText : palette.inkSecondary;
    final bg = selected ? palette.activeChipBg : Colors.transparent;

    return Material(
      color: bg,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: SizedBox(
          width: 58,
          height: 52,
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Stack(
                clipBehavior: Clip.none,
                children: [
                  Icon(icon, size: 20, color: fg),
                  if (badgeCount > 0)
                    Positioned(
                      right: -9,
                      top: -6,
                      child: _UnreadCountBadge(count: badgeCount),
                    ),
                ],
              ),
              const SizedBox(height: 3),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: fg,
                  fontSize: scope.sp(11.0),
                  fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// =============================================================================
// Mobile Floating Frosted Pill Navigation Bar
// =============================================================================

class _MobileFrostedPillNavBar extends StatelessWidget {
  final LinDoThemePalette palette;
  final MainTab activeTab;
  final int unreadBadgeCount;
  final ValueChanged<MainTab> onSelectTab;
  final VoidCallback onTapPublish;

  const _MobileFrostedPillNavBar({
    required this.palette,
    required this.activeTab,
    required this.unreadBadgeCount,
    required this.onSelectTab,
    required this.onTapPublish,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);

    return DecoratedBox(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(999),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: palette.isDark ? 0.36 : 0.10),
            blurRadius: 24,
            offset: const Offset(0, 8),
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(999),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 20, sigmaY: 20),
          child: Container(
            height: 62,
            padding: const EdgeInsets.symmetric(horizontal: 10),
            decoration: BoxDecoration(
              color: palette.navBarSurface.withValues(
                alpha: palette.isDark ? 0.84 : 0.88,
              ),
              borderRadius: BorderRadius.circular(999),
              border: Border.all(
                color: palette.hairline.withValues(alpha: 0.9),
                width: 0.9,
              ),
            ),
            child: Row(
              children: [
                Expanded(
                  child: _MobilePillNavButton(
                    palette: palette,
                    scope: scope,
                    icon: LinDoIcons.Home,
                    label: MainTab.HOME.title,
                    selected: activeTab == MainTab.HOME,
                    onTap: () => onSelectTab(MainTab.HOME),
                  ),
                ),
                Expanded(
                  child: _MobilePillNavButton(
                    palette: palette,
                    scope: scope,
                    icon: LinDoIcons.Compass,
                    label: MainTab.DISCOVER.title,
                    selected: activeTab == MainTab.DISCOVER,
                    onTap: () => onSelectTab(MainTab.DISCOVER),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 6),
                  child: Semantics(
                    button: true,
                    label: LinDoFeedCopy.PUBLISH_NEW_TOPIC_DESC.text,
                    child: Material(
                      color: palette.publishBtnBg,
                      borderRadius: BorderRadius.circular(999),
                      child: InkWell(
                        borderRadius: BorderRadius.circular(999),
                        onTap: onTapPublish,
                        child: SizedBox(
                          width: 48,
                          height: 40,
                          child: Icon(
                            LinDoIcons.Plus,
                            size: 21,
                            color: palette.publishBtnContent,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                Expanded(
                  child: _MobilePillNavButton(
                    palette: palette,
                    scope: scope,
                    icon: LinDoIcons.Bell,
                    label: MainTab.MESSAGES.title,
                    selected: activeTab == MainTab.MESSAGES,
                    badgeCount: unreadBadgeCount,
                    onTap: () => onSelectTab(MainTab.MESSAGES),
                  ),
                ),
                Expanded(
                  child: _MobilePillNavButton(
                    palette: palette,
                    scope: scope,
                    icon: LinDoIcons.User,
                    label: MainTab.PROFILE.title,
                    selected: activeTab == MainTab.PROFILE,
                    onTap: () => onSelectTab(MainTab.PROFILE),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _MobilePillNavButton extends StatelessWidget {
  final LinDoThemePalette palette;
  final LinDoThemeScope scope;
  final IconData icon;
  final String label;
  final bool selected;
  final int badgeCount;
  final VoidCallback onTap;

  const _MobilePillNavButton({
    required this.palette,
    required this.scope,
    required this.icon,
    required this.label,
    required this.selected,
    this.badgeCount = 0,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final fg = selected ? palette.inkPrimary : palette.inkTertiary;

    return Material(
      color: selected
          ? palette.activeChipBg.withValues(alpha: 0.72)
          : Colors.transparent,
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 6),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Stack(
                clipBehavior: Clip.none,
                children: [
                  Icon(icon, size: 19.5, color: fg),
                  if (badgeCount > 0)
                    Positioned(
                      right: -10,
                      top: -6,
                      child: _UnreadCountBadge(count: badgeCount),
                    ),
                ],
              ),
              const SizedBox(height: 2),
              Text(
                label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: fg,
                  fontSize: scope.sp(10.5),
                  fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// =============================================================================
// Shared Live Unread Badge & Floating Toast
// =============================================================================

class _UnreadCountBadge extends StatelessWidget {
  final int count;

  const _UnreadCountBadge({required this.count});

  @override
  Widget build(BuildContext context) {
    final label =
        count > 99 ? LinDoCommonCopy.BADGE_OVERFLOW_99.text : '$count';
    return Container(
      constraints: const BoxConstraints(minWidth: 16, minHeight: 16),
      padding: const EdgeInsets.symmetric(horizontal: 4.5, vertical: 1),
      decoration: BoxDecoration(
        color: const Color(0xFFEF4444),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(
          color: LinDoThemeScope.paletteOf(context).navBarSurface,
          width: 1.4,
        ),
      ),
      alignment: Alignment.center,
      child: Text(
        label,
        style: const TextStyle(
          color: Colors.white,
          fontSize: 9.5,
          fontWeight: FontWeight.w800,
          height: 1.1,
          fontFeatures: [FontFeature.tabularFigures()],
        ),
      ),
    );
  }
}

class _FloatingToastOverlay extends StatelessWidget {
  final String message;

  const _FloatingToastOverlay({required this.message});

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return Positioned(
      left: 24,
      right: 24,
      bottom: 88,
      child: IgnorePointer(
        child: Center(
          child: Material(
            color: Colors.transparent,
            child: Container(
              constraints: const BoxConstraints(maxWidth: 420),
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
              decoration: BoxDecoration(
                color: palette.isDark
                    ? const Color(0xEE222733)
                    : const Color(0xEE18181B),
                borderRadius: BorderRadius.circular(999),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.22),
                    blurRadius: 16,
                    offset: const Offset(0, 6),
                  ),
                ],
              ),
              child: Text(
                message,
                textAlign: TextAlign.center,
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: Colors.white,
                  fontSize: scope.sp(13.0),
                  fontWeight: FontWeight.w600,
                  height: 1.35,
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
