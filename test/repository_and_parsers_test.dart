import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:lindo/core/copy/lindo_copy.dart';
import 'package:lindo/core/diagnostics/lindo_diagnostic_logger.dart';
import 'package:lindo/core/models/lindo_models.dart';
import 'package:lindo/core/network/linux_do_repository.dart';
import 'package:lindo/core/platform/lindo_platform_channels.dart';
import 'package:lindo/core/render/discourse_ast_renderer.dart';
import 'package:lindo/core/render/markdown_preview.dart';
import 'package:lindo/core/site/linux_do_emoji_catalog.dart';
import 'package:lindo/core/site/linux_do_site_rules.dart';
import 'package:lindo/core/storage/lindo_local_store.dart';
import 'package:lindo/ui/theme/lindo_theme.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late Directory tempDir;

  setUp(() async {
    tempDir = await Directory.systemTemp.createTemp('lindo_core_test_');
    LinDoPlatformBridge.instance.debugStateDirOverride = tempDir;
    LinDoBrowserBridge.instance.debugForceAvailable = true;
  });

  tearDown(() async {
    LinDoBrowserBridge.instance.debugFetchOverride = null;
    LinDoBrowserBridge.instance.debugForceAvailable = null;
    LinDoPlatformBridge.instance.debugStateDirOverride = null;
    if (tempDir.existsSync()) {
      await tempDir.delete(recursive: true);
    }
  });

  group('Site Rules, Emoji Catalog, & AST / Markdown Parsers', () {
    test('resolves Discourse avatars, upload:// URLs, and internal topic links', () {
      final avatar = resolveDiscourseAvatarUrl(
        '/user_avatar/linux.do/neo/{size}/123_2.png',
        size: 96,
      );
      expect(avatar, 'https://linux.do/user_avatar/linux.do/neo/96/123_2.png');

      final uploadUrl = resolveDiscourseUrl('upload://abcDEF123456.png');
      expect(uploadUrl, 'https://linux.do/uploads/short-url/abcDEF123456.png');

      final parsed = parseInternalTopicUrl('https://linux.do/t/flutter-client/654321/18');
      expect(parsed, isNotNull);
      expect(parsed!.topicId, 654321);
      expect(parsed.postNumber, 18);
    });

    test('merges custom /emojis.json groups and resolves skin tones', () {
      final catalog = LinuxDoEmojiCatalog.instance;
      expect(catalog.resolveEmojiUrl(':rocket:'), contains('rocket.png'));
      expect(catalog.resolveEmojiUrl('thumbsup:t3'), isNotNull);

      catalog.mergeRemoteEmojisJson({
        '飞书': [
          {'name': 'feishu_001', 'url': '/uploads/default/original/feishu_001.png'},
        ],
      });
      expect(
        catalog.resolveEmojiUrl(':feishu_001:'),
        'https://linux.do/uploads/default/original/feishu_001.png',
      );
      expect(catalog.search('feishu_001'), isNotEmpty);
    });

    test('parses Discourse cooked HTML & Markdown workbench preview into AST nodes', () {
      final html = renderMarkdownToCookedHtml('''
[quote="alice, post:3, topic:99"]
同意楼上观点！
[/quote]

> [!TIP]
> 使用 Flutter Impeller 渲染丝滑 120Hz 列表。

```dart
void main() => print('Hello Lindo');
```

[spoiler]
隐藏彩蛋内容
[/spoiler]

欢迎 @bob 体验 :rocket:！
''');
      final nodes = parseDiscourseCookedHtml(html);
      expect(nodes.whereType<AstQuoteBlock>(), isNotEmpty);
      expect(nodes.whereType<AstCalloutBlock>(), isNotEmpty);
      expect(nodes.whereType<AstCodeBlock>(), isNotEmpty);
      expect(nodes.whereType<AstSpoilerBlock>(), isNotEmpty);
    });
  });

  group('Diagnostic Logger & Local Store Persistence', () {
    test('redacts sensitive cookies/tokens, persists logs, and clears cleanly', () async {
      final logger = LinDoDiagnosticLogger.instance;
      await logger.initialize(stateDir: tempDir);
      await logger.clearLogs();

      logger.info(
        DiagnosticCategory.AUTH,
        'SessionTest',
        'Cookie: _t=super_secret_token_value; cf_clearance=secret_cf; X-CSRF-Token: my_csrf_123',
      );
      final report = logger.buildDiagnosticReport(username: 'tester', isAuthorized: true);
      expect(report, contains('0.3.0'));
      expect(report, isNot(contains('super_secret_token_value')));
      expect(report, isNot(contains('secret_cf')));
      expect(report, isNot(contains('my_csrf_123')));

      await logger.clearLogs();
      expect(logger.entries, isEmpty);
    });

    test('persists preferences, read floors, local drafts, and clears content cache safely', () async {
      final store = LinDoLocalStore(stateDirOverride: tempDir);
      await store.initialize();

      await store.updatePreferences(
        store.preferences.copyWith(
          brandTheme: BrandThemeOption.GEEK_BLUE,
          isCompactFeed: true,
          fontScale: 1.08,
          debugModeUnlocked: true,
        ),
      );
      await store.saveSession(
        user: const AuthenticatedUserProfile(
          userId: 42,
          username: 'lindo_dev',
          displayName: 'Lindo Dev',
          trustLevel: 3,
        ),
        csrfToken: 'csrf_ok',
      );
      await store.recordTopicVisit(
        topicId: 888,
        title: 'Flutter 桌面端丝滑体验',
        categoryName: '开发调优',
        lastReadPostNumber: 15,
        postsCount: 30,
      );
      await store.saveLocalDraft(
        const CloudDraftItem(
          draftKey: 'new_topic',
          action: 'createTopic',
          title: '本地草稿标题',
          raw: '草稿正文内容',
          isLocalOnly: true,
        ),
      );

      // Re-open store from disk to verify persistence
      final reloaded = LinDoLocalStore(stateDirOverride: tempDir);
      await reloaded.initialize();
      expect(reloaded.preferences.brandTheme, BrandThemeOption.GEEK_BLUE);
      expect(reloaded.preferences.isCompactFeed, isTrue);
      expect(reloaded.preferences.debugModeUnlocked, isTrue);
      expect(reloaded.savedUser?.username, 'lindo_dev');
      expect(reloaded.getReadFloor(888), 15);
      expect(reloaded.getLocalDraft('new_topic')?.title, '本地草稿标题');

      await reloaded.clearContentCache();
      // Auth session and preferences remain intact after clearing content cache!
      expect(reloaded.savedUser?.username, 'lindo_dev');
      expect(reloaded.preferences.brandTheme, BrandThemeOption.GEEK_BLUE);
      expect(reloaded.readHistory, isEmpty);
    });
  });

  group('LinuxDoRepository Unread Badge Sync, Connect Parser, & Site Verification', () {
    test('immediately detects unread replies from /notifications.json on startup even if /session/current.json lags', () async {
      final store = LinDoLocalStore(stateDirOverride: tempDir);
      await store.initialize();
      await store.saveSession(
        user: const AuthenticatedUserProfile(
          userId: 7,
          username: 'lucas',
          displayName: 'Lucas',
          trustLevel: 3,
        ),
        csrfToken: 'csrf_init',
      );

      LinDoBrowserBridge.instance.debugFetchOverride = ({
        required String method,
        required String url,
        required Map<String, String> headers,
        String? body,
      }) async {
        if (url.contains('/session/current.json')) {
          // Simulate Discourse counter lag: unread_notifications = 0 even though 2 new replies exist!
          return BrowserHttpResponse(
            status: 200,
            url: url,
            csrfToken: 'csrf_live',
            body: jsonEncode({
              'current_user': {
                'id': 7,
                'username': 'lucas',
                'name': 'Lucas',
                'avatar_template': '/user_avatar/linux.do/lucas/{size}/1.png',
                'trust_level': 3,
                'unread_notifications': 0,
                'unread_high_priority_notifications': 0,
                'all_unread_notifications_count': 0,
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
              'notifications': [
                {
                  'id': 9001,
                  'notification_type': 2, // REPLIED
                  'read': false,
                  'created_at': '2026-11-13T06:00:00.000Z',
                  'topic_id': 101,
                  'post_number': 5,
                  'fancy_title': '关于 Flutter 重构的讨论',
                  'data': {
                    'topic_title': '关于 Flutter 重构的讨论',
                    'display_username': 'alice',
                  },
                },
                {
                  'id': 9002,
                  'notification_type': 1, // MENTIONED
                  'read': false,
                  'created_at': '2026-11-13T06:02:00.000Z',
                  'topic_id': 102,
                  'post_number': 2,
                  'fancy_title': 'macOS 百度拼音输入优化',
                  'data': {
                    'topic_title': 'macOS 百度拼音输入优化',
                    'display_username': 'bob',
                  },
                },
                {
                  'id': 9003,
                  'notification_type': 5,
                  'read': true,
                  'created_at': '2026-11-12T06:00:00.000Z',
                  'topic_id': 100,
                  'post_number': 1,
                  'data': {'topic_title': '旧通知', 'display_username': 'eve'},
                },
              ],
            }),
          );
        }
        return BrowserHttpResponse(status: 200, url: url, body: '{}');
      };

      final repo = LinuxDoRepository(store: store);
      addTearDown(repo.dispose);

      await repo.bootstrap();
      // Must immediately reflect the 2 unread notifications on app launch without opening Messages tab first!
      expect(repo.unreadNotificationCount, 2);
      expect(repo.totalUnreadBadgeCount, 2);
    });

    test('parses connect.linux.do HTML requirements and handles Cloudflare site verification cleanly', () async {
      final store = LinDoLocalStore(stateDirOverride: tempDir);
      await store.initialize();

      var simulateCfChallenge = false;
      LinDoBrowserBridge.instance.debugFetchOverride = ({
        required String method,
        required String url,
        required Map<String, String> headers,
        String? body,
      }) async {
        if (simulateCfChallenge) {
          return BrowserHttpResponse(
            status: 403,
            url: url,
            headers: const {'cf-mitigated': 'challenge'},
            body: '<!DOCTYPE html><html><head><title>Just a moment...</title></head><body></body></html>',
          );
        }
        if (url.contains('connect.linux.do')) {
          return const BrowserHttpResponse(
            status: 200,
            url: 'https://connect.linux.do/',
            body: '''
<html><body>
  <h1>你好，Lucas (3级用户) - 过去 100 天内统计</h1>
  <table>
    <tr><th>项目</th><th>当前</th><th>要求</th></tr>
    <tr class="text-green-600"><td>访问天数</td><td>68天</td><td>50%</td></tr>
    <tr class="text-green-600"><td>浏览话题</td><td>520</td><td>400</td></tr>
    <tr class="text-red-500"><td>获赞：点赞数</td><td>18</td><td>20</td></tr>
  </table>
</body></html>
''',
          );
        }
        return BrowserHttpResponse(status: 200, url: url, body: '{}');
      };

      final repo = LinuxDoRepository(store: store);
      addTearDown(repo.dispose);

      final connect = await repo.fetchConnectOverview();
      expect(connect.rows.length, 3);
      expect(connect.rows.first.label, contains('访问天数'));
      expect(connect.allQualified, isFalse);

      simulateCfChallenge = true;
      await expectLater(
        () => repo.fetchFeed(sort: FeedSortFilter.LATEST),
        throwsA(isA<LinuxDoException>().having((e) => e.isSiteVerification, 'isSiteVerification', isTrue)),
      );
      expect(repo.siteVerificationRequired, isTrue);

      simulateCfChallenge = false;
      await repo.onSiteVerificationPassed(csrfToken: 'csrf_verified');
      expect(repo.siteVerificationRequired, isFalse);
    });
  });
}
