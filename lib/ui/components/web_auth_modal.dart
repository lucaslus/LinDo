import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/platform/lindo_platform_channels.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../theme/lindo_theme.dart';
import 'lindo_icons.dart';
import 'lindo_mascot.dart';

enum WebAuthModalMode { login, siteVerify, inAppBrowser }

class WebAuthModalResult {
  final bool authenticated;
  final bool siteVerified;
  final String rawCookies;
  final String csrfToken;
  final Map<String, dynamic>? userPayload;

  const WebAuthModalResult({
    this.authenticated = false,
    this.siteVerified = false,
    this.rawCookies = '',
    this.csrfToken = '',
    this.userPayload,
  });
}

Future<WebAuthModalResult?> showLinDoWebAuthModal(
  BuildContext context, {
  required String initialUrl,
  WebAuthModalMode mode = WebAuthModalMode.login,
  String? customTitle,
}) {
  return showDialog<WebAuthModalResult>(
    context: context,
    barrierDismissible: false,
    barrierColor: Colors.black.withValues(alpha: 0.46),
    builder: (dialogContext) => LinDoWebAuthModal(
      initialUrl: initialUrl,
      mode: mode,
      customTitle: customTitle,
    ),
  );
}

class LinDoWebAuthModal extends StatefulWidget {
  final String initialUrl;
  final WebAuthModalMode mode;
  final String? customTitle;

  const LinDoWebAuthModal({
    super.key,
    required this.initialUrl,
    this.mode = WebAuthModalMode.login,
    this.customTitle,
  });

  @override
  State<LinDoWebAuthModal> createState() => _LinDoWebAuthModalState();
}

class _LinDoWebAuthModalState extends State<LinDoWebAuthModal> {
  final GlobalKey _viewportKey = GlobalKey();
  StreamSubscription<BrowserModalEvent>? _eventSub;

  late String _currentUrl;
  String _pageTitle = '';
  double _progress = 0.15;
  String? _errorMessage;

  bool _modalOpened = false;
  bool _closingOrCompleted = false;
  Rect? _lastReportedNormRect;

  @override
  void initState() {
    super.initState();
    final resolved = resolveDiscourseUrl(widget.initialUrl);
    _currentUrl = resolved.isEmpty ? kLinuxDoBaseUrl : resolved;
    _eventSub = LinDoBrowserBridge.instance.modalEvents.listen(_onModalEvent);
    WidgetsBinding.instance.addPostFrameCallback((_) => _syncNativeViewportRect());
  }

  void _onModalEvent(BrowserModalEvent ev) {
    if (!mounted || _closingOrCompleted) return;

    switch (ev.kind) {
      case BrowserModalEventKind.pageStarted:
        setState(() {
          if (ev.url.trim().isNotEmpty) _currentUrl = ev.url.trim();
          _errorMessage = null;
          _progress = 0.18;
        });
        break;
      case BrowserModalEventKind.progress:
        setState(() {
          if (ev.url.trim().isNotEmpty) _currentUrl = ev.url.trim();
          _progress = ev.progress.clamp(0.0, 1.0);
        });
        break;
      case BrowserModalEventKind.titleChanged:
        setState(() {
          if (ev.url.trim().isNotEmpty) _currentUrl = ev.url.trim();
          if (ev.title.trim().isNotEmpty) _pageTitle = ev.title.trim();
        });
        break;
      case BrowserModalEventKind.loginAuthenticated:
        _finishWithResult(
          WebAuthModalResult(
            authenticated: true,
            rawCookies: ev.rawCookies,
            csrfToken: ev.csrfToken,
            userPayload: ev.userPayload,
          ),
        );
        break;
      case BrowserModalEventKind.siteVerified:
        _finishWithResult(
          WebAuthModalResult(
            siteVerified: true,
            rawCookies: ev.rawCookies,
            csrfToken: ev.csrfToken,
          ),
        );
        break;
      case BrowserModalEventKind.loadError:
        setState(() {
          _errorMessage = (ev.errorMessage ?? '').trim().isNotEmpty
              ? ev.errorMessage!.trim()
              : LinDoAuthCopy.WEB_MODAL_CRASHED.text;
          _progress = 1.0;
        });
        break;
      case BrowserModalEventKind.closed:
        _closeByUser();
        break;
    }
  }

  Future<void> _finishWithResult(WebAuthModalResult result) async {
    if (_closingOrCompleted) return;
    _closingOrCompleted = true;
    await LinDoBrowserBridge.instance.closeModal();
    if (!mounted) return;
    Navigator.of(context).pop(result);
  }

  Future<void> _closeByUser() async {
    if (_closingOrCompleted) return;
    _closingOrCompleted = true;
    await LinDoBrowserBridge.instance.closeModal();
    if (!mounted) return;
    Navigator.of(context).pop();
  }

  void _syncNativeViewportRect() {
    if (!mounted || _closingOrCompleted) return;
    if (!LinDoBrowserBridge.instance.isAvailable) return;

    final renderObj = _viewportKey.currentContext?.findRenderObject();
    if (renderObj is! RenderBox || !renderObj.hasSize) return;

    final windowSize = MediaQuery.sizeOf(context);
    if (windowSize.width <= 1 || windowSize.height <= 1) return;

    final topLeft = renderObj.localToGlobal(Offset.zero);
    final boxSize = renderObj.size;
    if (boxSize.width <= 1 || boxSize.height <= 1) return;

    final normRect = Rect.fromLTWH(
      (topLeft.dx / windowSize.width).clamp(0.0, 1.0),
      (topLeft.dy / windowSize.height).clamp(0.0, 1.0),
      (boxSize.width / windowSize.width).clamp(0.05, 1.0),
      (boxSize.height / windowSize.height).clamp(0.05, 1.0),
    );

    final palette = LinDoThemeScope.paletteOf(context);
    if (!_modalOpened) {
      _modalOpened = true;
      _lastReportedNormRect = normRect;
      LinDoBrowserBridge.instance.openInteractiveModal(
        url: _currentUrl,
        mode: widget.mode.name,
        normLeft: normRect.left,
        normTop: normRect.top,
        normWidth: normRect.width,
        normHeight: normRect.height,
        isDark: palette.isDark,
      );
      return;
    }

    final prev = _lastReportedNormRect;
    if (prev == null ||
        (prev.left - normRect.left).abs() > 0.0015 ||
        (prev.top - normRect.top).abs() > 0.0015 ||
        (prev.width - normRect.width).abs() > 0.0015 ||
        (prev.height - normRect.height).abs() > 0.0015) {
      _lastReportedNormRect = normRect;
      LinDoBrowserBridge.instance.updateModalRect(
        normLeft: normRect.left,
        normTop: normRect.top,
        normWidth: normRect.width,
        normHeight: normRect.height,
      );
    }
  }

  @override
  void dispose() {
    _eventSub?.cancel();
    LinDoBrowserBridge.instance.closeModal();
    super.dispose();
  }

  String _resolveHeaderTitle() {
    final custom = widget.customTitle?.trim();
    if (custom != null && custom.isNotEmpty) return custom;
    switch (widget.mode) {
      case WebAuthModalMode.login:
        return LinDoAuthCopy.WEB_MODAL_LOGIN_AND_VERIFY_TITLE.text;
      case WebAuthModalMode.siteVerify:
        return LinDoAuthCopy.WEB_MODAL_VERIFY_RETURN_TITLE.text;
      case WebAuthModalMode.inAppBrowser:
        return _pageTitle.isNotEmpty
            ? _pageTitle
            : LinDoCommonCopy.LINUX_DO.text;
    }
  }

  String _resolveDomainHost() {
    final parsed = Uri.tryParse(_currentUrl);
    final host = parsed?.host.trim() ?? '';
    return host.isNotEmpty ? host : 'linux.do';
  }

  String _resolvePlaceholderSubtitle() {
    if (_errorMessage != null && _errorMessage!.isNotEmpty) {
      return _errorMessage!;
    }
    switch (widget.mode) {
      case WebAuthModalMode.login:
        return LinDoAuthCopy.WEB_MODAL_LOGIN_SUBTITLE.text;
      case WebAuthModalMode.siteVerify:
        return LinDoAuthCopy.WEB_MODAL_VERIFY_SUBTITLE.text;
      case WebAuthModalMode.inAppBrowser:
        return LinDoAuthCopy.WEB_MODAL_DEFAULT_SUBTITLE.text;
    }
  }

  void _handleReload() {
    setState(() {
      _errorMessage = null;
      _progress = 0.2;
    });
    if (LinDoBrowserBridge.instance.isAvailable) {
      if (_modalOpened) {
        LinDoBrowserBridge.instance.reloadModal();
      } else {
        _syncNativeViewportRect();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final screen = MediaQuery.sizeOf(context);
    final cardWidth = math.max(280.0, math.min(560.0, screen.width - 32.0));
    final cardHeight = math.max(320.0, math.min(680.0, screen.height - 48.0));
    final bridgeAvailable = LinDoBrowserBridge.instance.isAvailable;
    final showProgress = _progress > 0.0 && _progress < 0.995;

    return Dialog(
      backgroundColor: Colors.transparent,
      elevation: 0,
      insetPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
      child: Container(
        width: cardWidth,
        height: cardHeight,
        decoration: BoxDecoration(
          color: palette.cardSurface,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(color: palette.hairline, width: 0.9),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: palette.isDark ? 0.48 : 0.16),
              blurRadius: 32,
              offset: const Offset(0, 14),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          children: [
            SizedBox(
              height: 52,
              child: Column(
                children: [
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 10),
                      child: Row(
                        children: [
                          const SizedBox(width: 4),
                          Icon(
                            LinDoIcons.FaLock,
                            size: 14,
                            color: palette.logoDoColor,
                          ),
                          const SizedBox(width: 7),
                          Expanded(
                            child: Row(
                              children: [
                                Flexible(
                                  child: Text(
                                    _resolveHeaderTitle(),
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      color: palette.inkPrimary,
                                      fontSize: scope.sp(14.0),
                                      fontWeight: FontWeight.w700,
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 7),
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 7,
                                    vertical: 2,
                                  ),
                                  decoration: BoxDecoration(
                                    color: palette.subtleSurface,
                                    borderRadius: BorderRadius.circular(999),
                                    border: Border.all(
                                      color: palette.hairline,
                                      width: 0.7,
                                    ),
                                  ),
                                  child: Text(
                                    _resolveDomainHost(),
                                    maxLines: 1,
                                    style: TextStyle(
                                      color: palette.inkTertiary,
                                      fontSize: scope.sp(10.5),
                                      fontWeight: FontWeight.w600,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                          IconButton(
                            onPressed: () =>
                                LinDoBrowserBridge.instance.goBackModal(),
                            tooltip: LinDoCommonCopy.BACK.text,
                            visualDensity: VisualDensity.compact,
                            iconSize: 17,
                            color: palette.inkSecondary,
                            icon: const Icon(LinDoIcons.ArrowLeft),
                          ),
                          IconButton(
                            onPressed: _handleReload,
                            tooltip: LinDoAuthCopy.REFRESH_WEB_PAGE.text,
                            visualDensity: VisualDensity.compact,
                            iconSize: 17,
                            color: palette.inkSecondary,
                            icon: const Icon(LinDoIcons.Refresh),
                          ),
                          IconButton(
                            onPressed: () => LinDoPlatformBridge.instance
                                .openExternalUrl(_currentUrl),
                            tooltip: widget.mode == WebAuthModalMode.inAppBrowser
                                ? LinDoReaderCopy.ORIGINAL_WEB_POST.text
                                : LinDoAuthCopy.SYSTEM_BROWSER_AUTH.text,
                            visualDensity: VisualDensity.compact,
                            iconSize: 16,
                            color: palette.inkSecondary,
                            icon: const Icon(LinDoIcons.ExternalLink),
                          ),
                          IconButton(
                            onPressed: _closeByUser,
                            tooltip: LinDoAuthCopy.CLOSE_WEB_PAGE.text,
                            visualDensity: VisualDensity.compact,
                            iconSize: 18,
                            color: palette.inkPrimary,
                            icon: const Icon(LinDoIcons.Close),
                          ),
                        ],
                      ),
                    ),
                  ),
                  SizedBox(
                    height: 2,
                    child: showProgress
                        ? LinearProgressIndicator(
                            value: _progress.clamp(0.05, 1.0),
                            backgroundColor: palette.hairline,
                            valueColor: AlwaysStoppedAnimation<Color>(
                              palette.logoDoColor,
                            ),
                            minHeight: 2,
                          )
                        : Divider(
                            height: 1,
                            thickness: 0.8,
                            color: palette.hairline,
                          ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: LayoutBuilder(
                builder: (context, _) {
                  WidgetsBinding.instance.addPostFrameCallback(
                    (_) => _syncNativeViewportRect(),
                  );
                  return Container(
                    key: _viewportKey,
                    width: double.infinity,
                    height: double.infinity,
                    color: palette.canvas,
                    child: (!bridgeAvailable || _errorMessage != null)
                        ? _buildViewportPlaceholder(context)
                        : const SizedBox.expand(),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildViewportPlaceholder(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            LinDoMascot(
              mood: _errorMessage != null
                  ? MascotMood.surprised
                  : MascotMood.connecting,
              size: 72,
              animate: true,
            ),
            const SizedBox(height: 16),
            Text(
              _resolveHeaderTitle(),
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.inkPrimary,
                fontSize: scope.sp(16.0),
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 6),
            Text(
              _resolvePlaceholderSubtitle(),
              textAlign: TextAlign.center,
              style: TextStyle(
                color: palette.inkSecondary,
                fontSize: scope.sp(12.5),
                height: 1.5,
              ),
            ),
            const SizedBox(height: 14),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              decoration: BoxDecoration(
                color: palette.subtleSurface,
                borderRadius: BorderRadius.circular(10),
                border: Border.all(color: palette.hairline, width: 0.8),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    LinDoIcons.Globe,
                    size: 13,
                    color: palette.inkTertiary,
                  ),
                  const SizedBox(width: 6),
                  Flexible(
                    child: Text(
                      _currentUrl,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.inkSecondary,
                        fontSize: scope.sp(11.5),
                      ),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 18),
            Wrap(
              spacing: 10,
              runSpacing: 8,
              alignment: WrapAlignment.center,
              children: [
                FilledButton.icon(
                  onPressed: _handleReload,
                  style: FilledButton.styleFrom(
                    backgroundColor: palette.publishBtnBg,
                    foregroundColor: palette.publishBtnContent,
                    elevation: 0,
                    minimumSize: const Size(0, 38),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  icon: const Icon(LinDoIcons.Refresh, size: 15),
                  label: Text(
                    LinDoCommonCopy.RETRY.text,
                    style: TextStyle(
                      fontSize: scope.sp(13.0),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                OutlinedButton.icon(
                  onPressed: () =>
                      LinDoPlatformBridge.instance.openExternalUrl(_currentUrl),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: palette.inkPrimary,
                    side: BorderSide(color: palette.hairline, width: 0.9),
                    minimumSize: const Size(0, 38),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  icon: const Icon(LinDoIcons.ExternalLink, size: 15),
                  label: Text(
                    LinDoAuthCopy.SYSTEM_BROWSER_AUTH.text,
                    style: TextStyle(
                      fontSize: scope.sp(12.5),
                      fontWeight: FontWeight.w500,
                    ),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
