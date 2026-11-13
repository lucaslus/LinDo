import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lindo/core/copy/lindo_copy.dart';
import 'package:lindo/core/diagnostics/lindo_diagnostic_logger.dart';
import 'package:lindo/core/network/linux_do_repository.dart';
import 'package:lindo/core/platform/lindo_platform_channels.dart';
import 'package:lindo/core/storage/lindo_local_store.dart';
import 'package:lindo/lindo_app.dart';
import 'package:lindo/ui/components/lindo_brand_widgets.dart';
import 'package:lindo/ui/components/web_auth_modal.dart';
import 'package:lindo/ui/screens/discover_pane.dart';
import 'package:lindo/ui/screens/home_feed_pane.dart';
import 'package:lindo/ui/screens/messages_pane.dart';
import 'package:lindo/ui/screens/profile_pane.dart';
import 'package:lindo/ui/screens/topic_reader_pane.dart';
import 'package:lindo/ui/sheets/debug_diagnostics_sheet.dart';

void main() {
  late Directory tempDir;

  setUp(() async {
    tempDir = await Directory.systemTemp.createTemp('lindo_e2e_test_');
    LinDoPlatformBridge.instance.debugStateDirOverride = tempDir;
    LinDoBrowserBridge.instance.debugForceAvailable = true;
    await LinDoDiagnosticLogger.instance.initialize(stateDir: tempDir);
  });

  tearDown(() async {
    LinDoBrowserBridge.instance.debugFetchOverride = null;
    LinDoBrowserBridge.instance.debugForceAvailable = null;
    LinDoPlatformBridge.instance.debugStateDirOverride = null;
    if (tempDir.existsSync()) {
      await tempDir.delete(recursive: true);
    }
  });

  void installMockLinuxDoBackend({int unreadReplies = 3}) {
    LinDoBrowserBridge.instance.debugFetchOverride = ({
      required String method,
      required String url,
      required Map<String, String> headers,
      String? body,
    }) async {
      if (url.contains('/session/current.json')) {
        return BrowserHttpResponse(
          status: 200,
          url: url,
          csrfToken: 'csrf_e2e',
          body: jsonEncode({
            'current_user': {
              'id': 88,
              'username': 'lucas',
              'name': 'Lucas',
              'avatar_template': '/user_avatar/linux.do/lucas/{size}/1.png',
              'trust_level': 3,
              'unread_notifications': unreadReplies,
              'unread_high_priority_notifications': 0,
              'all_unread_notifications_count': unreadReplies,
              'unread_private_messages': 0,
            },
          }),
        );
      }
      if (url.contains('/notifications.json')) {
        return BrowserHttpResponse(
          status: 200,
          url: url,
          body: jsonEncode({
            'notifications': List.generate(
              unreadReplies,
              (i) => {
                'id': 5000 + i,
                'notification_type': 2,
                'read': false,
                'created_at': '2026-11-13T06:10:00.000Z',
                'topic_id': 777,
                'post_number': 2 + i,
                'fancy_title': 'Flutter 120Hz 原生重构体验报告',
                'data': {
                  'topic_title': 'Flutter 120Hz 原生重构体验报告',
                  'display_username': 'reviewer_$i',
                },
              },
            ),
          }),
        );
      }
      if (url.contains('/latest.json') || url.contains('/top')) {
        return BrowserHttpResponse(
          status: 200,
          url: url,
          body: jsonEncode({
            'users': [
              {
                'id': 88,
                'username': 'lucas',
                'name': 'Lucas',
                'avatar_template': '/user_avatar/linux.do/lucas/{size}/1.png',
                'trust_level': 3,
              },
            ],
            'topic_list': {
              'topics': [
                {
                  'id': 777,
                  'title': 'Flutter 120Hz 原生重构体验报告',
                  'fancy_title': 'Flutter 120Hz 原生重构体验报告',
                  'slug': 'flutter-120hz-report',
                  'posts_count': 4,
                  'reply_count': 3,
                  'highest_post_number': 4,
                  'views': 1280,
                  'like_count': 64,
                  'category_id': 4,
                  'tags': ['flutter', 'macos'],
                  'pinned': false,
                  'created_at': '2026-11-13T05:00:00.000Z',
                  'last_posted_at': '2026-11-13T06:10:00.000Z',
                  'posters': [
                    {'user_id': 88, 'description': 'Original Poster'},
                  ],
                },
              ],
            },
          }),
        );
      }
      if (url.contains('/t/777')) {
        return BrowserHttpResponse(
          status: 200,
          url: url,
          body: jsonEncode({
            'id': 777,
            'title': 'Flutter 120Hz 原生重构体验报告',
            'category_id': 4,
            'views': 1280,
            'like_count': 64,
            'posts_count': 2,
            'tags': ['flutter', 'macos'],
            'post_stream': {
              'stream': [1001, 1002],
              'posts': [
                {
                  'id': 1001,
                  'post_number': 1,
                  'user_id': 88,
                  'username': 'lucas',
                  'name': 'Lucas',
                  'trust_level': 3,
                  'cooked': '<p>桌面端滚动如丝般顺滑，且彻底告别 Cookie 粘贴！</p>',
                  'created_at': '2026-11-13T05:00:00.000Z',
                  'like_count': 32,
                },
                {
                  'id': 1002,
                  'post_number': 2,
                  'user_id': 99,
                  'username': 'alice',
                  'name': 'Alice',
                  'trust_level': 2,
                  'cooked': '<p>百度拼音打字也完全正常了！</p>',
                  'created_at': '2026-11-13T06:00:00.000Z',
                  'like_count': 12,
                },
              ],
            },
          }),
        );
      }
      if (url.contains('/u/lucas/summary.json') || url.contains('/u/lucas.json')) {
        return BrowserHttpResponse(
          status: 200,
          url: url,
          body: jsonEncode({
            'user': {
              'id': 88,
              'username': 'lucas',
              'name': 'Lucas',
              'trust_level': 3,
              'title': '架构师',
            },
            'user_summary': {
              'days_visited': 120,
              'likes_given': 256,
              'likes_received': 512,
              'topic_count': 18,
              'post_count': 340,
              'time_read': 86400,
            },
          }),
        );
      }
      return BrowserHttpResponse(status: 200, url: url, body: '{}');
    };
  }

  testWidgets(
    'Welcome screen has ONLY Web Login & Guest Mode (zero Cookie paste), auto-completes Web modal login, and renders Desktop split reader + unread badge',
    (tester) async {
      tester.view.physicalSize = const Size(1280, 820);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);

      installMockLinuxDoBackend(unreadReplies: 3);

      final store = LinDoLocalStore(stateDirOverride: tempDir);
      await store.initialize();
      final repo = LinuxDoRepository(store: store);
      addTearDown(repo.dispose);

      await tester.pumpWidget(LinDoRootApp(store: store, repository: repo));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));

      // 1. Welcome screen renders with ONLY login & guest buttons — ZERO Cookie paste UI!
      expect(find.byType(LinDoWelcomeScreen), findsOneWidget);
      expect(find.text(LinDoAuthCopy.LOGIN_WITH_LINUX_DO.text), findsOneWidget);
      expect(find.text(LinDoAuthCopy.BROWSE_AS_GUEST.text), findsOneWidget);
      expect(find.textContaining('Cookie'), findsNothing);
      expect(find.textContaining('cURL'), findsNothing);
      expect(find.textContaining('回调链接'), findsNothing);

      // 2. Tap Web Login -> opens in-app LinDoWebAuthModal
      await tester.tap(find.text(LinDoAuthCopy.LOGIN_WITH_LINUX_DO.text));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.byType(LinDoWebAuthModal), findsOneWidget);
      expect(find.textContaining('Cookie'), findsNothing);

      // 3. Emit automatic loginAuthenticated event from native WebView bridge -> modal auto-closes!
      LinDoBrowserBridge.instance.emitDebugModalEvent(
        const BrowserModalEvent(
          kind: BrowserModalEventKind.loginAuthenticated,
          url: 'https://linux.do/',
          csrfToken: 'csrf_e2e',
          userPayload: {
            'id': 88,
            'username': 'lucas',
            'name': 'Lucas',
            'trust_level': 3,
          },
        ),
      );
      for (var i = 0; i < 12; i++) {
        await tester.pump(const Duration(milliseconds: 200));
      }

      expect(repo.isAuthorized, isTrue);
      expect(repo.unreadNotificationCount, 3);
      expect(find.byType(HomeFeedPane), findsOneWidget);
      expect(find.byType(TopicReaderPane), findsOneWidget);
      expect(find.text('Flutter 120Hz 原生重构体验报告'), findsWidgets);
      // Unread badge '3' immediately visible on side rail!
      expect(find.text('3'), findsWidgets);

      // 4. Tap topic in feed -> loads floor #1 and floor #2 in desktop right split pane!
      await tester.tap(find.text('Flutter 120Hz 原生重构体验报告').first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.textContaining('桌面端滚动如丝般顺滑'), findsOneWidget);
      expect(find.textContaining('百度拼音打字也完全正常了'), findsOneWidget);

      // 5. Switch to Discover, Messages, and Profile tabs & unlock 5-tap Debug Mode!
      await tester.tap(find.text(LinDoFeedCopy.TAB_DISCOVER.text).first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.byType(DiscoverPane), findsOneWidget);

      await tester.tap(find.text(LinDoFeedCopy.TAB_MESSAGES.text).first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.byType(MessagesPane), findsOneWidget);

      await tester.tap(find.text(LinDoFeedCopy.TAB_PROFILE.text).first);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 250));
      expect(find.byType(ProfilePane), findsOneWidget);

      // Scroll ProfilePane to DebugVersionUnlockTrigger and tap 5 times
      final trigger = find.byType(DebugVersionUnlockTrigger);
      await tester.ensureVisible(trigger);
      await tester.pump();
      for (var i = 0; i < 5; i++) {
        await tester.tap(trigger);
        await tester.pump(const Duration(milliseconds: 50));
      }
      expect(store.preferences.debugModeUnlocked, isTrue);

      // Switch viewport to Mobile (412 x 915) -> verifies responsive mobile layout!
      tester.view.physicalSize = const Size(412, 915);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 200));
      expect(find.byType(ProfilePane), findsOneWidget);

      // Unmount cleanly so live notification polling timer disposes
      await tester.pumpWidget(const SizedBox.shrink());
      await tester.pump();
    },
  );
}
