import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:lindo/core/models/lindo_models.dart';
import 'package:lindo/core/network/linux_do_repository.dart';
import 'package:lindo/core/platform/lindo_platform_channels.dart';
import 'package:lindo/core/storage/lindo_local_store.dart';
import 'package:lindo/ui/components/composer_workbench.dart';
import 'package:lindo/ui/theme/lindo_theme.dart';

void main() {
  group('Apple Silicon Baidu Pinyin IME Safe Editor & Composer Tests', () {
    late Directory tempDir;
    late LinDoLocalStore store;
    late LinuxDoRepository repository;

    setUp(() async {
      tempDir = await Directory.systemTemp.createTemp('lindo_ime_test_');
      LinDoPlatformBridge.instance.debugStateDirOverride = tempDir;
      LinDoBrowserBridge.instance.debugForceAvailable = true;
      LinDoBrowserBridge.instance.debugFetchOverride = ({
        required String method,
        required String url,
        required Map<String, String> headers,
        String? body,
      }) async {
        return const BrowserHttpResponse(status: 200, body: '{}');
      };
      store = LinDoLocalStore(stateDirOverride: tempDir);
      await store.initialize();
      repository = LinuxDoRepository(store: store);
    });

    tearDown(() async {
      repository.dispose();
      LinDoBrowserBridge.instance.debugFetchOverride = null;
      LinDoBrowserBridge.instance.debugForceAvailable = null;
      LinDoPlatformBridge.instance.debugStateDirOverride = null;
      if (tempDir.existsSync()) {
        await tempDir.delete(recursive: true);
      }
    });

    testWidgets(
      'preserves multi-step Baidu Pinyin composing pre-edit without dropping or garbling characters',
      (tester) async {
        final editorController = LinDoImeSafeEditorController();
        addTearDown(editorController.dispose);

        final committedUpdates = <String>[];

        await tester.pumpWidget(
          MaterialApp(
            home: LinDoThemeScope(
              palette: buildLinDoPalette(isDark: true),
              child: Scaffold(
                body: LinDoImeSafeTextField(
                  controller: editorController,
                  autofocus: true,
                  hintText: '写下你的回复...',
                  onCommittedChanged: committedUpdates.add,
                ),
              ),
            ),
          ),
        );
        await tester.pump();

        // Step 1: Baidu Pinyin starts composing "b" -> "ba" -> "bai" -> "baidu"
        const step1 = TextEditingValue(
          text: 'b',
          selection: TextSelection.collapsed(offset: 1),
          composing: TextRange(start: 0, end: 1),
        );
        tester.testTextInput.updateEditingValue(step1);
        await tester.pump();
        expect(editorController.isComposing, isTrue);
        expect(editorController.textController.value.composing, const TextRange(start: 0, end: 1));
        expect(committedUpdates, isEmpty, reason: 'Must NOT fire committed change during active IME pre-edit');

        const step2 = TextEditingValue(
          text: 'bai du pin yin',
          selection: TextSelection.collapsed(offset: 14),
          composing: TextRange(start: 0, end: 14),
        );
        tester.testTextInput.updateEditingValue(step2);
        await tester.pump();
        expect(editorController.isComposing, isTrue);
        expect(editorController.textController.text, 'bai du pin yin');
        expect(committedUpdates, isEmpty);

        // Step 2: User selects candidate #1 -> Baidu Pinyin commits "百度拼音" and clears composing range
        const committed1 = TextEditingValue(
          text: '百度拼音',
          selection: TextSelection.collapsed(offset: 4),
          composing: TextRange.empty,
        );
        tester.testTextInput.updateEditingValue(committed1);
        await tester.pump();
        expect(editorController.isComposing, isFalse);
        expect(editorController.text, '百度拼音');
        expect(committedUpdates.last, '百度拼音');

        // Step 3: Append English " Flutter " + second Pinyin phrase "si hua" -> "丝滑"
        const englishAppend = TextEditingValue(
          text: '百度拼音 Flutter ',
          selection: TextSelection.collapsed(offset: 13),
          composing: TextRange.empty,
        );
        tester.testTextInput.updateEditingValue(englishAppend);
        await tester.pump();

        const secondComposing = TextEditingValue(
          text: '百度拼音 Flutter si hua',
          selection: TextSelection.collapsed(offset: 19),
          composing: TextRange(start: 13, end: 19),
        );
        tester.testTextInput.updateEditingValue(secondComposing);
        await tester.pump();
        expect(editorController.isComposing, isTrue);
        expect(committedUpdates.last, '百度拼音 Flutter ');

        const secondCommitted = TextEditingValue(
          text: '百度拼音 Flutter 丝滑体验！',
          selection: TextSelection.collapsed(offset: 18),
          composing: TextRange.empty,
        );
        tester.testTextInput.updateEditingValue(secondCommitted);
        await tester.pump();
        expect(editorController.text, '百度拼音 Flutter 丝滑体验！');
        expect(committedUpdates.last, '百度拼音 Flutter 丝滑体验！');
      },
    );

    testWidgets(
      'pressing Escape while Baidu Pinyin candidate window is active cancels pre-edit WITHOUT closing ComposerSheet',
      (tester) async {
        tester.view.physicalSize = const Size(1280, 820);
        tester.view.devicePixelRatio = 1.0;
        addTearDown(tester.view.resetPhysicalSize);
        addTearDown(tester.view.resetDevicePixelRatio);

        await tester.pumpWidget(
          MaterialApp(
            home: LinDoThemeScope(
              palette: buildLinDoPalette(isDark: true),
              child: Builder(
                builder: (ctx) => Scaffold(
                  body: Center(
                    child: ElevatedButton(
                      onPressed: () {
                        showLinDoComposerSheet(
                          ctx,
                          repository: repository,
                          store: store,
                          action: ComposerAction.REPLY,
                          topicId: 100,
                          topicTitle: '测试话题',
                        );
                      },
                      child: const Text('OPEN_COMPOSER'),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );

        await tester.tap(find.text('OPEN_COMPOSER'));
        await tester.pumpAndSettle();
        expect(find.byType(ComposerSheet), findsOneWidget);

        // Focus body editor and start active Pinyin composition
        final textFields = find.byType(EditableText);
        expect(textFields, findsWidgets);
        await tester.tap(textFields.last);
        await tester.pump();

        tester.testTextInput.updateEditingValue(
          const TextEditingValue(
            text: 'nihao',
            selection: TextSelection.collapsed(offset: 5),
            composing: TextRange(start: 0, end: 5),
          ),
        );
        await tester.pump();

        // Simulate pressing Escape to dismiss Baidu Pinyin candidate bar
        await tester.sendKeyEvent(LogicalKeyboardKey.escape);
        await tester.pump(const Duration(milliseconds: 50));

        // ComposerSheet MUST remain open!
        expect(find.byType(ComposerSheet), findsOneWidget);
      },
    );
  });
}
