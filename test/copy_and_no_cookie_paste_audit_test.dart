import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:lindo/core/copy/lindo_copy.dart';
import 'package:lindo/core/models/lindo_models.dart';
import 'package:lindo/ui/theme/lindo_theme.dart';

void main() {
  group('Global Copy Enum & Zero Cookie/cURL Paste Audit', () {
    test('allLinDoCopyEntries has 1000+ non-empty entries and zero Cookie/cURL paste prompts', () {
      final entries = allLinDoCopyEntries();
      expect(entries.length, greaterThanOrEqualTo(1000));

      const forbiddenSnippets = <String>[
        '粘贴 Cookie',
        '粘贴浏览器 Cookie',
        'Copy as cURL',
        '回调链接登录',
        '_t Cookie',
        '同步 cf_clearance',
        '从 Application 复制 _t',
      ];

      for (final entry in entries) {
        expect(entry.text.trim(), isNotEmpty);
        for (final bad in forbiddenSnippets) {
          expect(
            entry.text.contains(bad),
            isFalse,
            reason: 'Copy entry "${entry.text}" must NOT contain forbidden snippet "$bad"',
          );
        }
      }
    });

    test('all Dart files under lib/ contain zero manual Cookie/cURL paste UI strings', () {
      final libDir = Directory('lib');
      expect(libDir.existsSync(), isTrue);

      const forbiddenSourcePatterns = <String>[
        '粘贴 Cookie',
        '粘贴浏览器 Cookie',
        'Copy as cURL',
        '回调链接登录',
        '从 Application 复制',
      ];

      final dartFiles = libDir
          .listSync(recursive: true)
          .whereType<File>()
          .where((f) => f.path.endsWith('.dart'))
          .toList();
      expect(dartFiles, isNotEmpty);

      for (final file in dartFiles) {
        final content = file.readAsStringSync();
        for (final bad in forbiddenSourcePatterns) {
          expect(
            content.contains(bad),
            isFalse,
            reason: '${file.path} must NOT contain "$bad"',
          );
        }
      }
    });

    test('all 13 LinDoAppIcon choices and multi-DPI brand/badge assets exist on disk', () {
      final choices = LinDoAppIcon.choices;
      expect(choices.length, 13);
      expect(LinDoAppIcon.fromKey('happy_white'), LinDoAppIcon.defaultIcon);
      expect(LinDoAppIcon.fromKey('classic_amber'), LinDoAppIcon.classicIcon);

      for (final dpiDir in ['', '2.0x/', '3.0x/', '4.0x/']) {
        for (final name in ['mark.png', 'welcome.png', 'connecting.png']) {
          final f = File('assets/images/brand/$dpiDir$name');
          expect(f.existsSync(), isTrue, reason: 'Missing brand asset ${f.path}');
          expect(f.lengthSync(), greaterThan(500));
        }
      }

      for (final choice in choices) {
        final svg = choice.artwork.expressionSvgAsset;
        if (svg != null) {
          expect(File(svg).existsSync(), isTrue, reason: 'Missing expression SVG $svg');
        }
      }
      expect(File('assets/vectors/brand/linuxdo_mark.svg').existsSync(), isTrue);
      final badgeSvgs = Directory('assets/vectors/badges')
          .listSync()
          .whereType<File>()
          .where((f) => f.path.endsWith('.svg'))
          .toList();
      expect(badgeSvgs.length, greaterThanOrEqualTo(30));
    });

    test('snapFontSize aligns arbitrary scales to half-pixel grid without blurry sub-pixels', () {
      expect(snapFontSize(15.0, 0.92), 14.0); // 13.8 -> 14.0
      expect(snapFontSize(13.0, 0.92), 12.0); // 11.96 -> 12.0
      expect(snapFontSize(16.0, 1.08), 17.5); // 17.28 -> 17.5
      expect(snapFontSize(14.0, 1.0), 14.0);
    });

    test('BoostInputStats counts grapheme clusters, shortcodes, and emojis accurately', () {
      final ok = BoostInputStats.compute('太棒了 :rocket: 🎉');
      expect(ok.emojiCount, 2);
      expect(ok.canSubmit, isTrue);

      final tooManyEmojis =
          BoostInputStats.compute(':+1: :heart: :tada: :fire: :100: :eyes:');
      expect(tooManyEmojis.emojiCount, 6);
      expect(tooManyEmojis.exceedsEmojiLimit, isTrue);
      expect(tooManyEmojis.canSubmit, isFalse);

      final tooLong = BoostInputStats.compute('这是一段超过十六个可见字符的助力文案测试');
      expect(tooLong.exceedsLength, isTrue);
      expect(tooLong.canSubmit, isFalse);
    });

    test('UiErrorKind classifies site verification, guest login, permission, and transient errors', () {
      expect(
        UiErrorKind.classify('需要完成站点验证', isAuthorized: true),
        UiErrorKind.SITE_VERIFICATION,
      );
      expect(
        UiErrorKind.classify('请先登录后操作', isAuthorized: false),
        UiErrorKind.GUEST_LOGIN_REQUIRED,
      );
      expect(
        UiErrorKind.classify('信任等级不足，无权访问该分区', isAuthorized: true),
        UiErrorKind.AUTH_OR_PERMISSION,
      );
      expect(
        UiErrorKind.classify('网络连接超时，请稍后重试', isAuthorized: true),
        UiErrorKind.TRANSIENT_RECOVERABLE,
      );
    });
  });
}
