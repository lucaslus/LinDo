import 'package:flutter/material.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/site/linux_do_site_rules.dart';
import '../../core/storage/lindo_local_store.dart';
import '../components/composer_workbench.dart';
import '../components/lindo_icons.dart';
import '../components/lindo_mascot.dart';
import '../components/smart_avatar_and_badges.dart';
import '../sheets/badges_sheet.dart';
import '../sheets/user_collections_sheets.dart';
import '../theme/lindo_theme.dart';

class MessagesPane extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore store;
  final void Function(int topicId, {int? postNumber, String? title})
      onOpenTopic;
  final Future<void> Function() onStartWebLogin;
  final Future<void> Function() onStartSiteVerify;
  final ValueChanged<String>? onShowToast;

  const MessagesPane({
    super.key,
    required this.repository,
    required this.store,
    required this.onOpenTopic,
    required this.onStartWebLogin,
    required this.onStartSiteVerify,
    this.onShowToast,
  });

  @override
  State<MessagesPane> createState() => _MessagesPaneState();
}

class _MessagesPaneState extends State<MessagesPane> {
  MessagesSubTab _activeSubTab = MessagesSubTab.NOTIFICATIONS;

  // Notifications state
  NotificationCategoryFilter _notifFilter = NotificationCategoryFilter.ALL;
  bool _unreadOnlyNotif = false;
  List<UserNotificationItem> _notifications = const <UserNotificationItem>[];
  bool _loadingNotifs = false;
  bool _loadingMoreNotifs = false;
  bool _hasMoreNotifs = true;
  String? _notifError;

  // Private messages state
  PrivateMessagesViewMode _pmMode = PrivateMessagesViewMode.INBOX;
  List<TopicSummary> _pmTopics = const <TopicSummary>[];
  bool _loadingPms = false;
  String? _pmError;

  // Chat state
  List<ChatChannelItem> _channels = const <ChatChannelItem>[];
  bool _loadingChannels = false;
  String? _channelsError;
  ChatChannelItem? _selectedChannel;
  List<ChatMessageItem> _chatMessages = const <ChatMessageItem>[];
  bool _loadingChatMessages = false;
  bool _sendingChat = false;
  String? _chatMessagesError;
  final LinDoImeSafeEditorController _chatInputCtrl =
      LinDoImeSafeEditorController();
  final ScrollController _chatScrollCtrl = ScrollController();

  bool _wasAuthorized = false;

  @override
  void initState() {
    super.initState();
    _wasAuthorized = widget.repository.isAuthorized;
    widget.repository.addListener(_onRepositoryChanged);
    if (_wasAuthorized) {
      _loadActiveTab();
    }
  }

  @override
  void didUpdateWidget(covariant MessagesPane oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository) {
      oldWidget.repository.removeListener(_onRepositoryChanged);
      widget.repository.addListener(_onRepositoryChanged);
    }
  }

  @override
  void dispose() {
    widget.repository.removeListener(_onRepositoryChanged);
    _chatInputCtrl.dispose();
    _chatScrollCtrl.dispose();
    super.dispose();
  }

  void _onRepositoryChanged() {
    if (!mounted) return;
    final authNow = widget.repository.isAuthorized;
    if (!_wasAuthorized && authNow) {
      _wasAuthorized = true;
      _loadActiveTab();
      return;
    }
    if (_wasAuthorized && !authNow) {
      _wasAuthorized = false;
      setState(() {
        _notifications = const <UserNotificationItem>[];
        _pmTopics = const <TopicSummary>[];
        _channels = const <ChatChannelItem>[];
        _selectedChannel = null;
        _chatMessages = const <ChatMessageItem>[];
      });
      return;
    }
    setState(() {});
  }

  void _toast(String msg) {
    if (widget.onShowToast != null) {
      widget.onShowToast!(msg);
      return;
    }
    if (!mounted) return;
    ScaffoldMessenger.maybeOf(context)
      ?..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          content: Text(msg),
          behavior: SnackBarBehavior.floating,
          duration: const Duration(seconds: 2),
        ),
      );
  }

  void _loadActiveTab() {
    if (!widget.repository.isAuthorized) return;
    switch (_activeSubTab) {
      case MessagesSubTab.NOTIFICATIONS:
        _loadNotifications(reset: true);
        break;
      case MessagesSubTab.PRIVATE_MESSAGES:
        _loadPrivateMessages();
        break;
      case MessagesSubTab.CHAT:
        _loadChatChannels();
        break;
    }
  }

  // ---------------------------------------------------------------------------
  // Notifications
  // ---------------------------------------------------------------------------

  Future<void> _loadNotifications({required bool reset}) async {
    if (!widget.repository.isAuthorized) return;
    setState(() {
      if (reset) {
        _loadingNotifs = true;
        _notifError = null;
      } else {
        _loadingMoreNotifs = true;
      }
    });
    try {
      final offset = reset ? 0 : _notifications.length;
      final chunk = await widget.repository.fetchNotifications(
        offset: offset,
        unreadOnly: _unreadOnlyNotif,
      );
      if (!mounted) return;
      setState(() {
        _notifications = reset
            ? chunk
            : <UserNotificationItem>[..._notifications, ...chunk];
        _hasMoreNotifs = chunk.length >= 50;
        _loadingNotifs = false;
        _loadingMoreNotifs = false;
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _notifError = err.toString();
        _loadingNotifs = false;
        _loadingMoreNotifs = false;
      });
    }
  }

  Future<void> _markAllRead() async {
    try {
      await widget.repository.markAllNotificationsRead();
      await widget.repository.refreshCurrentSessionAndUnread(silent: true);
      if (!mounted) return;
      setState(() {
        _notifications = _notifications
            .map((n) => n.copyWith(read: true))
            .toList(growable: false);
      });
      _toast(LinDoCommonCopy.COMPLETED.text);
    } catch (_) {
      _toast(LinDoMessagesCopy.MARK_READ_FAILED.text);
    }
  }

  Future<void> _onTapNotification(UserNotificationItem item) async {
    if (!item.read) {
      setState(() {
        _notifications = _notifications
            .map((n) => n.id == item.id ? n.copyWith(read: true) : n)
            .toList(growable: false);
      });
      widget.repository.markNotificationRead(item.id).then((_) {
        widget.repository.refreshCurrentSessionAndUnread(silent: true);
      }).catchError((Object _) {});
    }

    if (item.kind == DiscourseNotificationKind.GRANTED_BADGE) {
      await showBadgesSheet(
        context,
        repository: widget.repository,
        onStartSiteVerify: widget.onStartSiteVerify,
        onOpenTopic: (topicId, {postNumber}) =>
            widget.onOpenTopic(topicId, postNumber: postNumber),
        onShowToast: widget.onShowToast,
      );
      return;
    }

    if (item.kind.isChat && item.chatChannelId > 0) {
      setState(() => _activeSubTab = MessagesSubTab.CHAT);
      await _loadChatChannels(autoSelectChannelId: item.chatChannelId);
      return;
    }

    if (item.kind.routesToPrivateMessages(item.topicId)) {
      setState(() => _activeSubTab = MessagesSubTab.PRIVATE_MESSAGES);
      await _loadPrivateMessages();
      return;
    }

    if (item.topicId > 0) {
      widget.onOpenTopic(
        item.topicId,
        postNumber: item.postNumber > 1 ? item.postNumber : null,
        title: item.title,
      );
    } else if (item.actorUsername != null) {
      await showPublicUserCardSheet(
        context,
        username: item.actorUsername!,
        repository: widget.repository,
        store: widget.store,
        onOpenTopic: widget.onOpenTopic,
        onStartSiteVerify: widget.onStartSiteVerify,
        onRequestGuestLogin: widget.onStartWebLogin,
        onShowToast: widget.onShowToast,
      );
    }
  }

  // ---------------------------------------------------------------------------
  // Private Messages
  // ---------------------------------------------------------------------------

  Future<void> _loadPrivateMessages() async {
    if (!widget.repository.isAuthorized) return;
    setState(() {
      _loadingPms = true;
      _pmError = null;
    });
    try {
      final list = await widget.repository.fetchPrivateMessages(mode: _pmMode);
      if (!mounted) return;
      setState(() {
        _pmTopics = list;
        _loadingPms = false;
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _pmError = err.toString();
        _loadingPms = false;
      });
    }
  }

  Future<void> _composePrivateMessage() async {
    final outcome = await showLinDoComposerSheet(
      context,
      repository: widget.repository,
      store: widget.store,
      action: ComposerAction.PRIVATE_MESSAGE,
      onStartSiteVerify: widget.onStartSiteVerify,
    );
    if (outcome != null && outcome.success) {
      _toast(
        outcome.enqueuedForReview
            ? LinDoMessagesCopy.PM_SUBMITTED_FOR_REVIEW.text
            : LinDoMessagesCopy.PM_SENT_SUCCESS.text,
      );
      _loadPrivateMessages();
      if (outcome.topicId != null) {
        widget.onOpenTopic(outcome.topicId!, title: outcome.title);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Discourse Chat
  // ---------------------------------------------------------------------------

  Future<void> _loadChatChannels({int? autoSelectChannelId}) async {
    if (!widget.repository.isAuthorized) return;
    setState(() {
      _loadingChannels = true;
      _channelsError = null;
    });
    try {
      final list = await widget.repository.fetchChatChannels();
      if (!mounted) return;
      ChatChannelItem? nextSelected = _selectedChannel;
      if (autoSelectChannelId != null) {
        for (final ch in list) {
          if (ch.id == autoSelectChannelId) {
            nextSelected = ch;
            break;
          }
        }
      }
      if (nextSelected == null && list.isNotEmpty) {
        final isWide = MediaQuery.sizeOf(context).width >= 760;
        if (isWide) {
          nextSelected = list.first;
        }
      }
      setState(() {
        _channels = list;
        _selectedChannel = nextSelected;
        _loadingChannels = false;
      });
      if (nextSelected != null) {
        _loadChatMessages(nextSelected.id);
      }
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _channelsError = err.toString();
        _loadingChannels = false;
      });
    }
  }

  Future<void> _loadChatMessages(int channelId) async {
    setState(() {
      _loadingChatMessages = true;
      _chatMessagesError = null;
    });
    try {
      final msgs = await widget.repository.fetchChatMessages(channelId);
      if (!mounted) return;
      setState(() {
        _chatMessages = msgs;
        _loadingChatMessages = false;
      });
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_chatScrollCtrl.hasClients) {
          _chatScrollCtrl.jumpTo(_chatScrollCtrl.position.maxScrollExtent);
        }
      });
    } catch (err) {
      if (!mounted) return;
      setState(() {
        _chatMessagesError = err.toString();
        _loadingChatMessages = false;
      });
    }
  }

  Future<void> _sendChatMessage() async {
    final channel = _selectedChannel;
    if (channel == null || _sendingChat) return;
    if (_chatInputCtrl.isComposing) return;
    final text = _chatInputCtrl.text.trim();
    if (text.isEmpty) return;

    setState(() => _sendingChat = true);
    try {
      final created = await widget.repository.sendChatMessage(channel.id, text);
      if (!mounted) return;
      _chatInputCtrl.setCommittedTextExternal('');
      if (created != null) {
        setState(() {
          _chatMessages = <ChatMessageItem>[..._chatMessages, created];
        });
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (_chatScrollCtrl.hasClients) {
            _chatScrollCtrl.animateTo(
              _chatScrollCtrl.position.maxScrollExtent,
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOutCubic,
            );
          }
        });
      } else {
        await _loadChatMessages(channel.id);
      }
    } catch (err) {
      _toast(err.toString());
    } finally {
      if (mounted) setState(() => _sendingChat = false);
    }
  }

  // ---------------------------------------------------------------------------
  // Build
  // ---------------------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);

    if (!widget.repository.isAuthorized) {
      return ColoredBox(
        color: palette.canvas,
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: LinDoStatePanel(
              mood: MascotMood.wink,
              title: LinDoAuthCopy.GUEST_PERSONAL_CENTER_TITLE.text,
              subtitle: LinDoMessagesCopy.GUEST_SYNC_HINT.text,
              actionLabel: LinDoAuthCopy.LOGIN_WITH_LINUX_DO.text,
              onAction: widget.onStartWebLogin,
            ),
          ),
        ),
      );
    }

    final unreadNotif = widget.repository.unreadNotificationCount;
    final unreadPm = widget.repository.unreadPmCount;
    final unreadChat =
        _channels.fold<int>(0, (sum, ch) => sum + ch.unreadCount);

    return ColoredBox(
      color: palette.canvas,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Header + Sub-tabs
          Padding(
            padding: const EdgeInsets.fromLTRB(18, 12, 12, 4),
            child: Row(
              children: [
                Icon(
                  LinDoIcons.BellSelected,
                  size: 21,
                  color: palette.logoDoColor,
                ),
                const SizedBox(width: 9),
                Expanded(
                  child: Text(
                    LinDoMessagesCopy.MESSAGES_TITLE.text,
                    style: TextStyle(
                      fontSize: scope.sp(20),
                      fontWeight: FontWeight.w800,
                      color: palette.inkPrimary,
                    ),
                  ),
                ),
                if (_activeSubTab == MessagesSubTab.NOTIFICATIONS &&
                    unreadNotif > 0)
                  TextButton.icon(
                    onPressed: _markAllRead,
                    icon: Icon(
                      LinDoIcons.CheckCircle,
                      size: 16,
                      color: palette.logoDoColor,
                    ),
                    label: Text(
                      LinDoMessagesCopy.MARK_ALL_AS_READ.text,
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        fontWeight: FontWeight.w700,
                        color: palette.inkPrimary,
                      ),
                    ),
                  ),
                if (_activeSubTab == MessagesSubTab.PRIVATE_MESSAGES)
                  FilledButton.tonalIcon(
                    style: FilledButton.styleFrom(
                      backgroundColor: palette.publishBtnBg,
                      foregroundColor: palette.publishBtnContent,
                      visualDensity: VisualDensity.compact,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                    onPressed: _composePrivateMessage,
                    icon: const Icon(LinDoIcons.Edit, size: 14),
                    label: Text(
                      LinDoMessagesCopy.PM_WRITE_ACTION.text,
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                IconButton(
                  tooltip: LinDoCommonCopy.REFRESH.text,
                  onPressed: _loadActiveTab,
                  icon: Icon(
                    LinDoIcons.Refresh,
                    size: 19,
                    color: palette.inkSecondary,
                  ),
                ),
              ],
            ),
          ),

          // Primary Sub-Tab Bar (通知 / 私信 / 聊天)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 18),
            child: Row(
              children: MessagesSubTab.values.map((tab) {
                final selected = tab == _activeSubTab;
                final badge = tab == MessagesSubTab.NOTIFICATIONS
                    ? unreadNotif
                    : tab == MessagesSubTab.PRIVATE_MESSAGES
                        ? unreadPm
                        : unreadChat;
                return Padding(
                  padding: const EdgeInsets.only(right: 22),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(8),
                    onTap: () {
                      if (selected) return;
                      setState(() => _activeSubTab = tab);
                      _loadActiveTab();
                    },
                    child: Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                tab.labelCopy.text,
                                style: TextStyle(
                                  fontSize: scope.sp(14.5),
                                  fontWeight: selected
                                      ? FontWeight.w800
                                      : FontWeight.w500,
                                  color: selected
                                      ? palette.inkPrimary
                                      : palette.inkSecondary,
                                ),
                              ),
                              if (badge > 0) ...[
                                const SizedBox(width: 5),
                                Container(
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 6,
                                    vertical: 1.5,
                                  ),
                                  decoration: BoxDecoration(
                                    color: const Color(0xFFEF4444),
                                    borderRadius: BorderRadius.circular(999),
                                  ),
                                  child: Text(
                                    badge > 99 ? '99+' : '$badge',
                                    style: TextStyle(
                                      fontSize: scope.sp(10.5),
                                      fontWeight: FontWeight.w800,
                                      color: Colors.white,
                                    ),
                                  ),
                                ),
                              ],
                            ],
                          ),
                          const SizedBox(height: 7),
                          Container(
                            height: 2.5,
                            width: selected ? 26 : 0,
                            decoration: BoxDecoration(
                              color: palette.tabIndicatorColor,
                              borderRadius: BorderRadius.circular(999),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
          Divider(height: 1, color: palette.hairline),

          Expanded(
            child: switch (_activeSubTab) {
              MessagesSubTab.NOTIFICATIONS =>
                _buildNotificationsTab(context, palette, scope),
              MessagesSubTab.PRIVATE_MESSAGES =>
                _buildPrivateMessagesTab(context, palette, scope),
              MessagesSubTab.CHAT => _buildChatTab(context, palette, scope),
            },
          ),
        ],
      ),
    );
  }

  Widget _buildNotificationsTab(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    final isSiteVerify = widget.repository.siteVerificationRequired ||
        _notifError.isSiteVerificationError;

    final filtered = _notifications.where((item) {
      if (_unreadOnlyNotif && item.read) return false;
      if (_notifFilter == NotificationCategoryFilter.ALL) return true;
      return item.kind.category == _notifFilter;
    }).toList(growable: false);

    return Column(
      children: [
        // Filter strip
        SizedBox(
          height: 42,
          child: ListView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
            children: [
              ...NotificationCategoryFilter.values.map((f) {
                final selected = f == _notifFilter;
                return Padding(
                  padding: const EdgeInsets.only(right: 8),
                  child: InkWell(
                    borderRadius: BorderRadius.circular(999),
                    onTap: () => setState(() => _notifFilter = f),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 12,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: selected
                            ? palette.activeChipBg
                            : palette.subtleSurface,
                        borderRadius: BorderRadius.circular(999),
                        border: Border.all(
                          color: selected
                              ? palette.activeChipText.withValues(alpha: 0.35)
                              : palette.hairline,
                        ),
                      ),
                      child: Center(
                        child: Text(
                          f.labelCopy.text,
                          style: TextStyle(
                            fontSize: scope.sp(12),
                            fontWeight:
                                selected ? FontWeight.w700 : FontWeight.w500,
                            color: selected
                                ? palette.activeChipText
                                : palette.inkSecondary,
                          ),
                        ),
                      ),
                    ),
                  ),
                );
              }),
              InkWell(
                borderRadius: BorderRadius.circular(999),
                onTap: () {
                  setState(() => _unreadOnlyNotif = !_unreadOnlyNotif);
                  _loadNotifications(reset: true);
                },
                child: Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
                  decoration: BoxDecoration(
                    color: _unreadOnlyNotif
                        ? palette.activeChipBg
                        : palette.subtleSurface,
                    borderRadius: BorderRadius.circular(999),
                    border: Border.all(
                      color: _unreadOnlyNotif
                          ? palette.activeChipText.withValues(alpha: 0.35)
                          : palette.hairline,
                    ),
                  ),
                  child: Center(
                    child: Text(
                      LinDoMessagesCopy.FILTER_UNREAD.text,
                      style: TextStyle(
                        fontSize: scope.sp(12),
                        fontWeight: _unreadOnlyNotif
                            ? FontWeight.w700
                            : FontWeight.w500,
                        color: _unreadOnlyNotif
                            ? palette.activeChipText
                            : palette.inkSecondary,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        Divider(height: 1, color: palette.hairline),
        Expanded(
          child: _loadingNotifs && _notifications.isEmpty
              ? LinDoStatePanel(
                  mood: MascotMood.connecting,
                  title: LinDoCommonCopy.LOADING.text,
                )
              : isSiteVerify
                  ? LinDoSiteVerificationCard(
                      onStartInAppVerify: () async {
                        await widget.onStartSiteVerify();
                        if (mounted) _loadNotifications(reset: true);
                      },
                      onRetry: () => _loadNotifications(reset: true),
                    )
                  : _notifError != null && _notifications.isEmpty
                      ? LinDoStatePanel(
                          mood: MascotMood.surprised,
                          title: LinDoCommonCopy.LOAD_FAILED.text,
                          subtitle: _notifError,
                          actionLabel: LinDoCommonCopy.RETRY.text,
                          onAction: () => _loadNotifications(reset: true),
                        )
                      : filtered.isEmpty
                          ? LinDoStatePanel(
                              mood: MascotMood.sleepy,
                              title: _unreadOnlyNotif
                                  ? LinDoMessagesCopy
                                      .EMPTY_UNREAD_NOTIFICATIONS.text
                                  : (_notifications.isNotEmpty
                                      ? LinDoMessagesCopy
                                          .NO_MATCHING_LOADED_NOTIFICATIONS.text
                                      : LinDoMessagesCopy
                                          .EMPTY_NOTIFICATIONS.text),
                              actionLabel: _hasMoreNotifs &&
                                      _notifications.isNotEmpty
                                  ? LinDoMessagesCopy
                                      .LOAD_EARLIER_NOTIFICATIONS.text
                                  : null,
                              onAction: _hasMoreNotifs &&
                                      _notifications.isNotEmpty
                                  ? () => _loadNotifications(reset: false)
                                  : null,
                            )
                          : RefreshIndicator(
                              color: palette.logoDoColor,
                              onRefresh: () => _loadNotifications(reset: true),
                              child: ListView.builder(
                                physics:
                                    const AlwaysScrollableScrollPhysics(),
                                padding: const EdgeInsets.only(bottom: 96),
                                itemCount: filtered.length +
                                    (_hasMoreNotifs ? 1 : 0),
                                itemBuilder: (context, index) {
                                  if (index >= filtered.length) {
                                    return Padding(
                                      padding: const EdgeInsets.symmetric(
                                        vertical: 16,
                                      ),
                                      child: Center(
                                        child: _loadingMoreNotifs
                                            ? const LinDoWaveDots()
                                            : OutlinedButton(
                                                onPressed: () =>
                                                    _loadNotifications(
                                                  reset: false,
                                                ),
                                                child: Text(
                                                  LinDoMessagesCopy
                                                      .LOAD_EARLIER_NOTIFICATIONS
                                                      .text,
                                                ),
                                              ),
                                      ),
                                    );
                                  }
                                  final item = filtered[index];
                                  return RepaintBoundary(
                                    child: _NotificationRowItem(
                                      item: item,
                                      onTap: () => _onTapNotification(item),
                                      onTapActor: item.actorUsername == null
                                          ? null
                                          : () => showPublicUserCardSheet(
                                                context,
                                                username: item.actorUsername!,
                                                repository: widget.repository,
                                                store: widget.store,
                                                onOpenTopic: widget.onOpenTopic,
                                                onStartSiteVerify:
                                                    widget.onStartSiteVerify,
                                                onRequestGuestLogin:
                                                    widget.onStartWebLogin,
                                                onShowToast: widget.onShowToast,
                                              ),
                                    ),
                                  );
                                },
                              ),
                            ),
        ),
      ],
    );
  }

  Widget _buildPrivateMessagesTab(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    final isSiteVerify = widget.repository.siteVerificationRequired ||
        _pmError.isSiteVerificationError;

    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Row(
            children: PrivateMessagesViewMode.mailboxTabs.map((mode) {
              final selected = mode == _pmMode;
              return Padding(
                padding: const EdgeInsets.only(right: 8),
                child: InkWell(
                  borderRadius: BorderRadius.circular(999),
                  onTap: () {
                    if (selected) return;
                    setState(() => _pmMode = mode);
                    _loadPrivateMessages();
                  },
                  child: Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 13,
                      vertical: 5,
                    ),
                    decoration: BoxDecoration(
                      color: selected
                          ? palette.activeChipBg
                          : palette.subtleSurface,
                      borderRadius: BorderRadius.circular(999),
                      border: Border.all(
                        color: selected
                            ? palette.activeChipText.withValues(alpha: 0.35)
                            : palette.hairline,
                      ),
                    ),
                    child: Text(
                      mode.tabLabel?.text ?? '',
                      style: TextStyle(
                        fontSize: scope.sp(12.5),
                        fontWeight:
                            selected ? FontWeight.w700 : FontWeight.w500,
                        color: selected
                            ? palette.activeChipText
                            : palette.inkSecondary,
                      ),
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
        ),
        Divider(height: 1, color: palette.hairline),
        Expanded(
          child: _loadingPms
              ? LinDoStatePanel(
                  mood: MascotMood.connecting,
                  title: LinDoCommonCopy.LOADING.text,
                )
              : isSiteVerify
                  ? LinDoSiteVerificationCard(
                      onStartInAppVerify: () async {
                        await widget.onStartSiteVerify();
                        if (mounted) _loadPrivateMessages();
                      },
                      onRetry: _loadPrivateMessages,
                    )
                  : _pmError != null
                      ? LinDoStatePanel(
                          mood: MascotMood.surprised,
                          title: LinDoCommonCopy.LOAD_FAILED.text,
                          subtitle: _pmError,
                          actionLabel: LinDoCommonCopy.RETRY.text,
                          onAction: _loadPrivateMessages,
                        )
                      : _pmTopics.isEmpty
                          ? LinDoStatePanel(
                              mood: MascotMood.sleepy,
                              title: _pmMode == PrivateMessagesViewMode.ARCHIVE
                                  ? LinDoMessagesCopy.PM_EMPTY_ARCHIVED.text
                                  : LinDoMessagesCopy
                                      .EMPTY_PRIVATE_MESSAGES.text,
                              actionLabel: LinDoMessagesCopy.PM_WRITE_ACTION.text,
                              onAction: _composePrivateMessage,
                            )
                          : RefreshIndicator(
                              color: palette.logoDoColor,
                              onRefresh: _loadPrivateMessages,
                              child: ListView.builder(
                                physics:
                                    const AlwaysScrollableScrollPhysics(),
                                padding:
                                    const EdgeInsets.fromLTRB(16, 12, 16, 96),
                                itemCount: _pmTopics.length,
                                itemBuilder: (context, index) {
                                  final pm = _pmTopics[index];
                                  final op = pm.originalPoster;
                                  final timeStr =
                                      pm.lastPostedAtIso.isNotEmpty
                                          ? formatRelativeTime(
                                              pm.lastPostedAtIso,
                                            )
                                          : formatRelativeTime(
                                              pm.lastPostedAt,
                                            );
                                  return RepaintBoundary(
                                    child: Padding(
                                      padding:
                                          const EdgeInsets.only(bottom: 10),
                                      child: Material(
                                        color: palette.subtleSurface,
                                        shape: RoundedRectangleBorder(
                                          borderRadius:
                                              BorderRadius.circular(14),
                                          side: BorderSide(
                                            color: palette.hairline,
                                          ),
                                        ),
                                        child: InkWell(
                                          borderRadius:
                                              BorderRadius.circular(14),
                                          onTap: () => widget.onOpenTopic(
                                            pm.id,
                                            title: pm.title,
                                          ),
                                          child: Padding(
                                            padding: const EdgeInsets.all(14),
                                            child: Row(
                                              children: [
                                                SmartAvatar(
                                                  avatarUrl: op.avatarTemplate,
                                                  fallbackLabel: op.username,
                                                  size: 42,
                                                  flair: op.flair,
                                                  animatedAvatar:
                                                      op.animatedAvatar,
                                                ),
                                                const SizedBox(width: 12),
                                                Expanded(
                                                  child: Column(
                                                    crossAxisAlignment:
                                                        CrossAxisAlignment
                                                            .start,
                                                    children: [
                                                      Row(
                                                        children: [
                                                          if (pm.unseen ||
                                                              pm.unreadPosts >
                                                                  0) ...[
                                                            Container(
                                                              width: 7,
                                                              height: 7,
                                                              decoration:
                                                                  BoxDecoration(
                                                                color: palette
                                                                    .logoDoColor,
                                                                shape: BoxShape
                                                                    .circle,
                                                              ),
                                                            ),
                                                            const SizedBox(
                                                              width: 6,
                                                            ),
                                                          ],
                                                          Expanded(
                                                            child: Text(
                                                              pm.title,
                                                              maxLines: 1,
                                                              overflow:
                                                                  TextOverflow
                                                                      .ellipsis,
                                                              style: TextStyle(
                                                                fontSize: scope
                                                                    .sp(14.5),
                                                                fontWeight:
                                                                    FontWeight
                                                                        .w700,
                                                                color: palette
                                                                    .inkPrimary,
                                                              ),
                                                            ),
                                                          ),
                                                          const SizedBox(
                                                            width: 8,
                                                          ),
                                                          Text(
                                                            timeStr,
                                                            style: TextStyle(
                                                              fontSize: scope
                                                                  .sp(11.5),
                                                              color: palette
                                                                  .inkTertiary,
                                                            ),
                                                          ),
                                                        ],
                                                      ),
                                                      const SizedBox(height: 4),
                                                      Text(
                                                        '@${op.username} · ${pm.postsCount} 条消息',
                                                        style: TextStyle(
                                                          fontSize:
                                                              scope.sp(12),
                                                          color: palette
                                                              .inkSecondary,
                                                        ),
                                                      ),
                                                    ],
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                        ),
                                      ),
                                    ),
                                  );
                                },
                              ),
                            ),
        ),
      ],
    );
  }

  Widget _buildChatTab(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    final isSiteVerify = widget.repository.siteVerificationRequired ||
        _channelsError.isSiteVerificationError;

    if (_loadingChannels && _channels.isEmpty) {
      return LinDoStatePanel(
        mood: MascotMood.connecting,
        title: LinDoCommonCopy.LOADING.text,
      );
    }

    if (isSiteVerify && _channels.isEmpty) {
      return LinDoSiteVerificationCard(
        onStartInAppVerify: () async {
          await widget.onStartSiteVerify();
          if (mounted) _loadChatChannels();
        },
        onRetry: _loadChatChannels,
      );
    }

    if (_channelsError != null && _channels.isEmpty) {
      return LinDoStatePanel(
        mood: MascotMood.surprised,
        title: LinDoCommonCopy.LOAD_FAILED.text,
        subtitle: _channelsError,
        actionLabel: LinDoCommonCopy.RETRY.text,
        onAction: _loadChatChannels,
      );
    }

    if (_channels.isEmpty) {
      return LinDoStatePanel(
        mood: MascotMood.sleepy,
        title: '暂无已加入的聊天频道',
        actionLabel: LinDoCommonCopy.REFRESH.text,
        onAction: _loadChatChannels,
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final isSplitChat = constraints.maxWidth >= 720;

        if (isSplitChat) {
          return Row(
            children: [
              SizedBox(
                width: 280,
                child: _buildChannelList(context, palette, scope),
              ),
              VerticalDivider(width: 1, color: palette.hairline),
              Expanded(
                child: _selectedChannel == null
                    ? const LinDoStatePanel(
                        mood: MascotMood.sleepy,
                        title: '选择左侧频道开始聊天',
                      )
                    : _buildChatThread(
                        context,
                        palette,
                        scope,
                        _selectedChannel!,
                        showBackButton: false,
                      ),
              ),
            ],
          );
        }

        if (_selectedChannel != null) {
          return _buildChatThread(
            context,
            palette,
            scope,
            _selectedChannel!,
            showBackButton: true,
          );
        }

        return _buildChannelList(context, palette, scope);
      },
    );
  }

  Widget _buildChannelList(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
  ) {
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 10, 12, 96),
      itemCount: _channels.length,
      itemBuilder: (context, index) {
        final ch = _channels[index];
        final selected = _selectedChannel?.id == ch.id;
        return RepaintBoundary(
          child: Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Material(
              color: selected ? palette.activeChipBg : palette.subtleSurface,
              borderRadius: BorderRadius.circular(13),
              child: InkWell(
                borderRadius: BorderRadius.circular(13),
                onTap: () {
                  setState(() => _selectedChannel = ch);
                  _loadChatMessages(ch.id);
                },
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 12,
                    vertical: 11,
                  ),
                  child: Row(
                    children: [
                      if (ch.isDirectMessage &&
                          (ch.avatarTemplate ?? '').isNotEmpty)
                        SmartAvatar(
                          avatarUrl: ch.avatarTemplate!,
                          fallbackLabel: ch.title,
                          size: 36,
                        )
                      else
                        Container(
                          width: 36,
                          height: 36,
                          decoration: BoxDecoration(
                            color: palette.tonalPill,
                            borderRadius: BorderRadius.circular(10),
                          ),
                          child: Icon(
                            ch.isDirectMessage
                                ? LinDoIcons.UserSelected
                                : LinDoIcons.MessageCircle,
                            size: 17,
                            color: palette.logoDoColor,
                          ),
                        ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Expanded(
                                  child: Text(
                                    ch.title,
                                    maxLines: 1,
                                    overflow: TextOverflow.ellipsis,
                                    style: TextStyle(
                                      fontSize: scope.sp(13.5),
                                      fontWeight: FontWeight.w700,
                                      color: palette.inkPrimary,
                                    ),
                                  ),
                                ),
                                if (ch.unreadCount > 0)
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 6,
                                      vertical: 1.5,
                                    ),
                                    decoration: BoxDecoration(
                                      color: palette.logoDoColor,
                                      borderRadius: BorderRadius.circular(999),
                                    ),
                                    child: Text(
                                      '${ch.unreadCount}',
                                      style: TextStyle(
                                        fontSize: scope.sp(10.5),
                                        fontWeight: FontWeight.w800,
                                        color: const Color(0xFF111827),
                                      ),
                                    ),
                                  ),
                              ],
                            ),
                            if ((ch.lastMessageExcerpt ?? '').isNotEmpty ||
                                ch.description.isNotEmpty) ...[
                              const SizedBox(height: 2),
                              Text(
                                ch.lastMessageExcerpt ?? ch.description,
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: scope.sp(11.5),
                                  color: palette.inkSecondary,
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildChatThread(
    BuildContext context,
    LinDoThemePalette palette,
    LinDoThemeScope scope,
    ChatChannelItem channel, {
    required bool showBackButton,
  }) {
    final myUsername =
        widget.repository.currentUser?.username.toLowerCase() ?? '';

    return Column(
      children: [
        // Channel header
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: palette.cardSurface,
            border: Border(bottom: BorderSide(color: palette.hairline)),
          ),
          child: Row(
            children: [
              if (showBackButton)
                IconButton(
                  visualDensity: VisualDensity.compact,
                  onPressed: () => setState(() => _selectedChannel = null),
                  icon: Icon(
                    LinDoIcons.ArrowLeft,
                    size: 19,
                    color: palette.inkPrimary,
                  ),
                ),
              const SizedBox(width: 4),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      channel.title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: scope.sp(14.5),
                        fontWeight: FontWeight.w700,
                        color: palette.inkPrimary,
                      ),
                    ),
                    if (channel.description.isNotEmpty)
                      Text(
                        channel.description,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: scope.sp(11.5),
                          color: palette.inkSecondary,
                        ),
                      ),
                  ],
                ),
              ),
              IconButton(
                visualDensity: VisualDensity.compact,
                onPressed: _loadingChatMessages
                    ? null
                    : () => _loadChatMessages(channel.id),
                icon: Icon(
                  LinDoIcons.Refresh,
                  size: 18,
                  color: palette.inkSecondary,
                ),
              ),
            ],
          ),
        ),

        // Messages list
        Expanded(
          child: _loadingChatMessages && _chatMessages.isEmpty
              ? LinDoStatePanel(
                  mood: MascotMood.connecting,
                  title: LinDoCommonCopy.LOADING.text,
                )
              : _chatMessagesError != null && _chatMessages.isEmpty
                  ? LinDoStatePanel(
                      mood: MascotMood.surprised,
                      title: LinDoCommonCopy.LOAD_FAILED.text,
                      subtitle: _chatMessagesError,
                      actionLabel: LinDoCommonCopy.RETRY.text,
                      onAction: () => _loadChatMessages(channel.id),
                    )
                  : _chatMessages.isEmpty
                      ? const LinDoStatePanel(
                          mood: MascotMood.sleepy,
                          title: '暂无聊天消息',
                        )
                      : ListView.builder(
                          controller: _chatScrollCtrl,
                          padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
                          itemCount: _chatMessages.length,
                          itemBuilder: (context, index) {
                            final msg = _chatMessages[index];
                            final isMe = myUsername.isNotEmpty &&
                                msg.username.toLowerCase() == myUsername;
                            final bodyText = msg.message.trim().isNotEmpty
                                ? msg.message.trim()
                                : decodeDiscourseHtmlSnippet(
                                    msg.cookedHtml,
                                    preserveLineBreaks: true,
                                  );
                            return RepaintBoundary(
                              child: Padding(
                                padding: const EdgeInsets.only(bottom: 12),
                                child: Row(
                                  crossAxisAlignment: CrossAxisAlignment.start,
                                  mainAxisAlignment: isMe
                                      ? MainAxisAlignment.end
                                      : MainAxisAlignment.start,
                                  children: [
                                    if (!isMe) ...[
                                      SmartAvatar(
                                        avatarUrl: msg.avatarTemplate,
                                        fallbackLabel: msg.username,
                                        size: 34,
                                        onTap: () => showPublicUserCardSheet(
                                          context,
                                          username: msg.username,
                                          repository: widget.repository,
                                          store: widget.store,
                                          onOpenTopic: widget.onOpenTopic,
                                          onStartSiteVerify:
                                              widget.onStartSiteVerify,
                                          onShowToast: widget.onShowToast,
                                        ),
                                      ),
                                      const SizedBox(width: 9),
                                    ],
                                    Flexible(
                                      child: Column(
                                        crossAxisAlignment: isMe
                                            ? CrossAxisAlignment.end
                                            : CrossAxisAlignment.start,
                                        children: [
                                          Row(
                                            mainAxisSize: MainAxisSize.min,
                                            children: [
                                              Text(
                                                msg.displayName.isEmpty
                                                    ? msg.username
                                                    : msg.displayName,
                                                style: TextStyle(
                                                  fontSize: scope.sp(11.5),
                                                  fontWeight: FontWeight.w700,
                                                  color: palette.inkSecondary,
                                                ),
                                              ),
                                              if (msg.createdAtText
                                                  .isNotEmpty) ...[
                                                const SizedBox(width: 6),
                                                Text(
                                                  msg.createdAtText,
                                                  style: TextStyle(
                                                    fontSize: scope.sp(10.5),
                                                    color: palette.inkTertiary,
                                                  ),
                                                ),
                                              ],
                                            ],
                                          ),
                                          const SizedBox(height: 4),
                                          Container(
                                            padding: const EdgeInsets.symmetric(
                                              horizontal: 13,
                                              vertical: 9,
                                            ),
                                            decoration: BoxDecoration(
                                              color: isMe
                                                  ? palette.activeChipBg
                                                  : palette.subtleSurface,
                                              borderRadius:
                                                  BorderRadius.circular(14),
                                              border: Border.all(
                                                color: palette.hairline,
                                              ),
                                            ),
                                            child: SelectableText(
                                              bodyText,
                                              style: TextStyle(
                                                fontSize: scope.sp(13.5),
                                                height: 1.45,
                                                color: palette.inkPrimary,
                                              ),
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            );
                          },
                        ),
        ),

        // IME-Safe Chat Composer Box
        SafeArea(
          top: false,
          child: Container(
            padding: EdgeInsets.fromLTRB(
              14,
              10,
              14,
              showBackButton ? 84 : 12,
            ),
            decoration: BoxDecoration(
              color: palette.cardSurface,
              border: Border(top: BorderSide(color: palette.hairline)),
            ),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: Container(
                    decoration: BoxDecoration(
                      color: palette.subtleSurface,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(color: palette.hairline),
                    ),
                    child: LinDoImeSafeTextField(
                      controller: _chatInputCtrl,
                      enableMarkdownShortcuts: false,
                      minLines: 1,
                      maxLines: 4,
                      textInputAction: TextInputAction.send,
                      hintText: '发送消息至 ${channel.title}…',
                      contentPadding: const EdgeInsets.symmetric(
                        horizontal: 13,
                        vertical: 10,
                      ),
                      onSubmitted: (_) => _sendChatMessage(),
                      onSubmitShortcut: _sendChatMessage,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                SizedBox(
                  height: 40,
                  width: 44,
                  child: FilledButton(
                    style: FilledButton.styleFrom(
                      padding: EdgeInsets.zero,
                      backgroundColor: palette.publishBtnBg,
                      foregroundColor: palette.publishBtnContent,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    onPressed: _sendingChat ? null : _sendChatMessage,
                    child: _sendingChat
                        ? const SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(LinDoIcons.Send, size: 17),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _NotificationRowItem extends StatelessWidget {
  final UserNotificationItem item;
  final VoidCallback onTap;
  final VoidCallback? onTapActor;

  const _NotificationRowItem({
    required this.item,
    required this.onTap,
    this.onTapActor,
  });

  IconData _kindFallbackIcon(DiscourseNotificationKind kind) {
    if (kind == DiscourseNotificationKind.GRANTED_BADGE) {
      return LinDoIcons.Trophy;
    }
    if (kind.isChat) {
      return LinDoIcons.MessageCircle;
    }
    switch (kind.category) {
      case NotificationCategoryFilter.REACTION:
        return LinDoIcons.HeartFilled;
      case NotificationCategoryFilter.MENTION:
        return LinDoIcons.AtSign;
      case NotificationCategoryFilter.REPLY:
        return LinDoIcons.Reply;
      case NotificationCategoryFilter.SYSTEM:
      case NotificationCategoryFilter.ALL:
        return LinDoIcons.Bell;
    }
  }

  @override
  Widget build(BuildContext context) {
    final palette = LinDoThemeScope.paletteOf(context);
    final scope = LinDoThemeScope.of(context);
    final actor = item.actorUsername;
    final cleanExcerpt = decodeDiscourseHtmlSnippet(item.excerpt);

    return Material(
      color: item.read
          ? palette.canvas
          : Color.alphaBlend(
              palette.logoDoColor.withValues(alpha: palette.isDark ? 0.08 : 0.05),
              palette.canvas,
            ),
      child: InkWell(
        onTap: onTap,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
          decoration: BoxDecoration(
            border: Border(bottom: BorderSide(color: palette.hairline)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Unread dot + Avatar
              Stack(
                clipBehavior: Clip.none,
                children: [
                  if ((item.actingAvatarTemplate ?? '').trim().isNotEmpty)
                    SmartAvatar(
                      avatarUrl: item.actingAvatarTemplate!,
                      fallbackLabel: actor ?? item.actingUsername,
                      size: 38,
                      flair: item.actingFlair,
                      animatedAvatar: item.actingAnimatedAvatar,
                      primaryGroupName: item.actingPrimaryGroupName,
                      onTap: onTapActor,
                    )
                  else
                    Container(
                      width: 38,
                      height: 38,
                      decoration: BoxDecoration(
                        color: palette.activeChipBg,
                        shape: BoxShape.circle,
                      ),
                      child: Icon(
                        _kindFallbackIcon(item.kind),
                        size: 18,
                        color: palette.activeChipText,
                      ),
                    ),
                  if (!item.read)
                    Positioned(
                      right: -1,
                      top: -1,
                      child: Container(
                        width: 9,
                        height: 9,
                        decoration: BoxDecoration(
                          color: palette.logoDoColor,
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: palette.canvas,
                            width: 1.5,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        if (actor != null) ...[
                          Flexible(
                            child: Text(
                              actor,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: scope.sp(13),
                                fontWeight: FontWeight.w700,
                                color: palette.inkPrimary,
                              ),
                            ),
                          ),
                          const SizedBox(width: 6),
                        ],
                        Expanded(
                          child: Text(
                            item.typeLabel,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                              fontSize: scope.sp(12.5),
                              color: palette.inkSecondary,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          formatRelativeTime(item.createdAtText),
                          style: TextStyle(
                            fontSize: scope.sp(11.5),
                            color: palette.inkTertiary,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 5),
                    Text(
                      item.title.isNotEmpty
                          ? item.title
                          : (item.badgeName.isNotEmpty
                              ? item.badgeName
                              : LinDoMessagesCopy.NOTIFICATIONS_TITLE.text),
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: scope.sp(14),
                        fontWeight:
                            item.read ? FontWeight.w600 : FontWeight.w700,
                        color: palette.inkPrimary,
                      ),
                    ),
                    if (cleanExcerpt.isNotEmpty) ...[
                      const SizedBox(height: 4),
                      Text(
                        cleanExcerpt,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: scope.sp(12.5),
                          color: palette.inkSecondary,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
