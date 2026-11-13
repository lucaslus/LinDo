import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/copy/lindo_copy.dart';
import '../../core/models/lindo_models.dart';
import '../../core/network/linux_do_repository.dart';
import '../../core/platform/lindo_platform_channels.dart';
import '../../core/render/markdown_preview.dart';
import '../../core/site/linux_do_emoji_catalog.dart';
import '../../core/storage/lindo_local_store.dart';
import '../theme/lindo_theme.dart';
import 'lindo_icons.dart';
import 'lindo_mascot.dart';
import 'post_rich_content.dart';
import 'smart_avatar_and_badges.dart';

class _EditorSnapshot {
  final String text;
  final TextSelection selection;
  final int timestampMs;

  const _EditorSnapshot({
    required this.text,
    required this.selection,
    required this.timestampMs,
  });
}

/// IME 安全编辑器控制器（针对 macOS Apple Silicon 百度拼音、搜狗拼音、系统拼音深度加固）：
/// 1. 严格检测 `composing.isValid && !composing.isCollapsed`；
/// 2. 拼音组字预编辑 (`markedText`) 期间绝不回写 `TextEditingValue`、绝不执行正则、绝不压入撤销栈；
/// 3. 记录组字结束时间戳，拦截 160ms 内因取消拼音候选窗而派发的 `Escape` 按键，杜绝误关弹窗与吞字乱码。
class LinDoImeSafeEditorController extends ChangeNotifier {
  static const int _maxHistory = 80;
  static const int _coalesceWindowMs = 480;

  final TextEditingController textController;
  final bool _ownsTextController;

  final List<_EditorSnapshot> _undoStack = <_EditorSnapshot>[];
  final List<_EditorSnapshot> _redoStack = <_EditorSnapshot>[];

  bool _wasComposingOnLastFrame = false;
  DateTime? _lastComposingEndTime;
  bool _applyingInternalMutation = false;
  String _lastCommittedText = '';

  LinDoImeSafeEditorController({
    String initialText = '',
    TextEditingController? existingController,
  })  : textController =
            existingController ?? TextEditingController(text: initialText),
        _ownsTextController = existingController == null {
    _lastCommittedText = textController.text;
    _undoStack.add(
      _EditorSnapshot(
        text: textController.text,
        selection: textController.selection.isValid
            ? textController.selection
            : TextSelection.collapsed(offset: textController.text.length),
        timestampMs: DateTime.now().millisecondsSinceEpoch,
      ),
    );
    textController.addListener(_handleRawControllerTick);
  }

  static bool isValueComposing(TextEditingValue value) {
    return value.composing.isValid && !value.composing.isCollapsed;
  }

  bool get isComposing => isValueComposing(textController.value);

  bool get isWithinPostComposingEscapeGrace {
    if (isComposing) return true;
    final endedAt = _lastComposingEndTime;
    if (endedAt == null) return false;
    return DateTime.now().difference(endedAt).inMilliseconds < 160;
  }

  String get text => textController.text;

  bool get canUndo => !isComposing && _undoStack.length > 1;

  bool get canRedo => !isComposing && _redoStack.isNotEmpty;

  void _handleRawControllerTick() {
    final val = textController.value;
    final composingNow = isValueComposing(val);

    if (_wasComposingOnLastFrame && !composingNow) {
      _lastComposingEndTime = DateTime.now();
    }
    _wasComposingOnLastFrame = composingNow;

    // 组字期间绝不记录快照或向外部触发提交态变更，100% 保护百度拼音预编辑缓冲区
    if (composingNow) {
      return;
    }

    if (_applyingInternalMutation) {
      notifyListeners();
      return;
    }

    if (val.text != _lastCommittedText) {
      _lastCommittedText = val.text;
      _recordCommittedSnapshot(val, allowCoalesce: true);
      notifyListeners();
    }
  }

  void _recordCommittedSnapshot(
    TextEditingValue val, {
    required bool allowCoalesce,
  }) {
    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final safeSel = val.selection.isValid
        ? val.selection
        : TextSelection.collapsed(offset: val.text.length);

    if (_undoStack.isNotEmpty && _undoStack.last.text == val.text) {
      _undoStack[_undoStack.length - 1] = _EditorSnapshot(
        text: val.text,
        selection: safeSel,
        timestampMs: nowMs,
      );
      return;
    }

    _redoStack.clear();

    if (allowCoalesce && _undoStack.length > 1) {
      final prev = _undoStack.last;
      final deltaLen = (val.text.length - prev.text.length).abs();
      if (nowMs - prev.timestampMs <= _coalesceWindowMs &&
          deltaLen <= 2 &&
          !val.text.endsWith('\n')) {
        _undoStack[_undoStack.length - 1] = _EditorSnapshot(
          text: val.text,
          selection: safeSel,
          timestampMs: nowMs,
        );
        return;
      }
    }

    _undoStack.add(
      _EditorSnapshot(
        text: val.text,
        selection: safeSel,
        timestampMs: nowMs,
      ),
    );
    while (_undoStack.length > _maxHistory) {
      _undoStack.removeAt(0);
    }
  }

  void setCommittedTextExternal(String nextText, {bool collapseToEnd = true}) {
    if (isComposing) return;
    if (textController.text == nextText) return;
    final offset = collapseToEnd
        ? nextText.length
        : textController.selection.extentOffset.clamp(0, nextText.length);
    final nextVal = TextEditingValue(
      text: nextText,
      selection: TextSelection.collapsed(offset: offset),
      composing: TextRange.empty,
    );
    _applyingInternalMutation = true;
    try {
      textController.value = nextVal;
      _lastCommittedText = nextText;
      _recordCommittedSnapshot(nextVal, allowCoalesce: false);
    } finally {
      _applyingInternalMutation = false;
    }
    notifyListeners();
  }

  void insertText(String snippet) {
    final val = textController.value;
    final currentText = val.text;
    final rawStart = val.selection.isValid ? val.selection.start : currentText.length;
    final rawEnd = val.selection.isValid ? val.selection.end : currentText.length;
    final start = math.min(rawStart, rawEnd).clamp(0, currentText.length);
    final end = math.max(rawStart, rawEnd).clamp(0, currentText.length);

    final updatedText = currentText.replaceRange(start, end, snippet);
    final nextCursor = (start + snippet.length).clamp(0, updatedText.length);
    final nextVal = TextEditingValue(
      text: updatedText,
      selection: TextSelection.collapsed(offset: nextCursor),
      composing: TextRange.empty,
    );

    _applyingInternalMutation = true;
    try {
      _wasComposingOnLastFrame = false;
      textController.value = nextVal;
      _lastCommittedText = updatedText;
      _recordCommittedSnapshot(nextVal, allowCoalesce: false);
    } finally {
      _applyingInternalMutation = false;
    }
    notifyListeners();
  }

  void wrapSelection(
    String prefix,
    String suffix, {
    String placeholder = '',
  }) {
    final val = textController.value;
    final currentText = val.text;
    final rawStart = val.selection.isValid ? val.selection.start : currentText.length;
    final rawEnd = val.selection.isValid ? val.selection.end : currentText.length;
    final start = math.min(rawStart, rawEnd).clamp(0, currentText.length);
    final end = math.max(rawStart, rawEnd).clamp(0, currentText.length);

    final selected = start < end ? currentText.substring(start, end) : '';
    final inner = selected.isNotEmpty ? selected : placeholder;
    final replacement = '$prefix$inner$suffix';
    final updatedText = currentText.replaceRange(start, end, replacement);

    final TextSelection nextSelection;
    if (selected.isEmpty && placeholder.isNotEmpty) {
      nextSelection = TextSelection(
        baseOffset: start + prefix.length,
        extentOffset: start + prefix.length + placeholder.length,
      );
    } else {
      nextSelection = TextSelection.collapsed(
        offset: (start + replacement.length).clamp(0, updatedText.length),
      );
    }

    final nextVal = TextEditingValue(
      text: updatedText,
      selection: nextSelection,
      composing: TextRange.empty,
    );

    _applyingInternalMutation = true;
    try {
      _wasComposingOnLastFrame = false;
      textController.value = nextVal;
      _lastCommittedText = updatedText;
      _recordCommittedSnapshot(nextVal, allowCoalesce: false);
    } finally {
      _applyingInternalMutation = false;
    }
    notifyListeners();
  }

  void undo() {
    if (!canUndo) return;
    final current = _undoStack.removeLast();
    _redoStack.add(current);
    final target = _undoStack.last;
    _applySnapshot(target);
  }

  void redo() {
    if (!canRedo) return;
    final target = _redoStack.removeLast();
    _undoStack.add(target);
    _applySnapshot(target);
  }

  void _applySnapshot(_EditorSnapshot snapshot) {
    final clampedBase =
        snapshot.selection.baseOffset.clamp(0, snapshot.text.length);
    final clampedExtent =
        snapshot.selection.extentOffset.clamp(0, snapshot.text.length);
    final nextVal = TextEditingValue(
      text: snapshot.text,
      selection: TextSelection(
        baseOffset: clampedBase,
        extentOffset: clampedExtent,
      ),
      composing: TextRange.empty,
    );
    _applyingInternalMutation = true;
    try {
      textController.value = nextVal;
      _lastCommittedText = snapshot.text;
    } finally {
      _applyingInternalMutation = false;
    }
    notifyListeners();
  }

  @override
  void dispose() {
    textController.removeListener(_handleRawControllerTick);
    if (_ownsTextController) {
      textController.dispose();
    }
    super.dispose();
  }
}

class LinDoImeSafeTextField extends StatefulWidget {
  final LinDoImeSafeEditorController? controller;
  final FocusNode? focusNode;
  final String initialText;
  final String? hintText;
  final ValueChanged<String>? onCommittedChanged;
  final VoidCallback? onSubmitShortcut;
  final VoidCallback? onInsertLinkShortcut;
  final bool enableMarkdownShortcuts;
  final int? minLines;
  final int? maxLines;
  final bool expands;
  final bool autofocus;
  final bool readOnly;
  final TextInputAction? textInputAction;
  final ValueChanged<String>? onSubmitted;
  final TextStyle? style;
  final TextStyle? hintStyle;
  final InputDecoration? decoration;
  final EdgeInsetsGeometry? contentPadding;

  const LinDoImeSafeTextField({
    super.key,
    this.controller,
    this.focusNode,
    this.initialText = '',
    this.hintText,
    this.onCommittedChanged,
    this.onSubmitShortcut,
    this.onInsertLinkShortcut,
    this.enableMarkdownShortcuts = true,
    this.minLines,
    this.maxLines,
    this.expands = false,
    this.autofocus = false,
    this.readOnly = false,
    this.textInputAction,
    this.onSubmitted,
    this.style,
    this.hintStyle,
    this.decoration,
    this.contentPadding,
  });

  @override
  State<LinDoImeSafeTextField> createState() => _LinDoImeSafeTextFieldState();
}

class _LinDoImeSafeTextFieldState extends State<LinDoImeSafeTextField> {
  LinDoImeSafeEditorController? _internalController;
  FocusNode? _internalFocusNode;
  String _lastDeliveredCommitted = '';

  LinDoImeSafeEditorController get _effectiveController =>
      widget.controller ?? (_internalController ??= LinDoImeSafeEditorController(initialText: widget.initialText));

  FocusNode get _effectiveFocusNode =>
      widget.focusNode ?? (_internalFocusNode ??= FocusNode());

  bool get isImeComposing {
    final v = _effectiveController.textController.value;
    return v.composing.isValid && !v.composing.isCollapsed;
  }

  @override
  void initState() {
    super.initState();
    _lastDeliveredCommitted = _effectiveController.text;
    _effectiveController.addListener(_onCommittedStateUpdated);
  }

  @override
  void didUpdateWidget(covariant LinDoImeSafeTextField oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller?.removeListener(_onCommittedStateUpdated);
      _internalController?.removeListener(_onCommittedStateUpdated);
      _lastDeliveredCommitted = _effectiveController.text;
      _effectiveController.addListener(_onCommittedStateUpdated);
    }
  }

  void _onCommittedStateUpdated() {
    if (isImeComposing) return;
    final current = _effectiveController.text;
    if (current != _lastDeliveredCommitted) {
      _lastDeliveredCommitted = current;
      widget.onCommittedChanged?.call(current);
    }
  }

  @override
  void dispose() {
    _effectiveController.removeListener(_onCommittedStateUpdated);
    _internalController?.dispose();
    _internalFocusNode?.dispose();
    super.dispose();
  }

  KeyEventResult _handleKeyEvent(FocusNode node, KeyEvent event) {
    // 1. 若刚结束或正处于百度拼音/系统输入法候选组字，吞掉取消候选窗产生的 Escape，绝不误关编辑器弹窗
    if (event.logicalKey == LogicalKeyboardKey.escape) {
      if (_effectiveController.isWithinPostComposingEscapeGrace) {
        return KeyEventResult.handled;
      }
      return KeyEventResult.ignored;
    }

    // 2. 拼音组字激活期间绝不拦截任何输入键，全权交给原生输入法引擎
    if (isImeComposing) {
      return KeyEventResult.ignored;
    }

    if (event is! KeyDownEvent && event is! KeyRepeatEvent) {
      return KeyEventResult.ignored;
    }

    final hw = HardwareKeyboard.instance;
    final isPrimary = hw.isMetaPressed || hw.isControlPressed;
    if (!isPrimary) return KeyEventResult.ignored;

    final key = event.logicalKey;
    final isShift = hw.isShiftPressed;

    if ((key == LogicalKeyboardKey.enter ||
            key == LogicalKeyboardKey.numpadEnter) &&
        event is KeyDownEvent &&
        widget.onSubmitShortcut != null) {
      widget.onSubmitShortcut!();
      return KeyEventResult.handled;
    }

    if (!widget.enableMarkdownShortcuts) {
      return KeyEventResult.ignored;
    }

    if (key == LogicalKeyboardKey.keyZ) {
      if (isShift) {
        if (_effectiveController.canRedo) _effectiveController.redo();
      } else {
        if (_effectiveController.canUndo) _effectiveController.undo();
      }
      return KeyEventResult.handled;
    }

    if (key == LogicalKeyboardKey.keyY && !hw.isMetaPressed) {
      if (_effectiveController.canRedo) _effectiveController.redo();
      return KeyEventResult.handled;
    }

    if (event is KeyDownEvent && !isShift) {
      if (key == LogicalKeyboardKey.keyB) {
        _effectiveController.wrapSelection(
          '**',
          '**',
          placeholder: LinDoComposerCopy.TOOLBAR_BOLD.text,
        );
        return KeyEventResult.handled;
      }
      if (key == LogicalKeyboardKey.keyI) {
        _effectiveController.wrapSelection(
          '*',
          '*',
          placeholder: LinDoComposerCopy.TOOL_ITALIC.text,
        );
        return KeyEventResult.handled;
      }
      if (key == LogicalKeyboardKey.keyK) {
        if (widget.onInsertLinkShortcut != null) {
          widget.onInsertLinkShortcut!();
        } else {
          _effectiveController.wrapSelection(
            '[',
            '](https://)',
            placeholder: LinDoComposerCopy.LINK_TEXT_LABEL.text,
          );
        }
        return KeyEventResult.handled;
      }
    }

    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    final resolvedStyle = widget.style ??
        TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(15.0),
          height: 1.58,
        );

    final resolvedDecoration = widget.decoration ??
        InputDecoration(
          hintText: widget.hintText,
          hintStyle: widget.hintStyle ??
              TextStyle(
                color: palette.inkTertiary,
                fontSize: resolvedStyle.fontSize,
              ),
          border: InputBorder.none,
          isCollapsed: widget.contentPadding != null,
          contentPadding: widget.contentPadding ??
              const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        );

    return Focus(
      onKeyEvent: _handleKeyEvent,
      child: TextField(
        controller: _effectiveController.textController,
        focusNode: _effectiveFocusNode,
        autofocus: widget.autofocus,
        readOnly: widget.readOnly,
        expands: widget.expands,
        minLines: widget.expands ? null : widget.minLines,
        maxLines: widget.expands ? null : widget.maxLines,
        textInputAction: widget.textInputAction,
        onSubmitted: (val) {
          if (!isImeComposing) {
            widget.onSubmitted?.call(val);
          }
        },
        keyboardType: (widget.maxLines == 1 && !widget.expands)
            ? TextInputType.text
            : TextInputType.multiline,
        style: resolvedStyle,
        cursorColor: palette.logoDoColor,
        enableIMEPersonalizedLearning: true,
        decoration: resolvedDecoration,
      ),
    );
  }
}

enum _WorkbenchViewMode { edit, preview, split }

class LinDoMarkdownWorkbench extends StatefulWidget {
  final LinDoImeSafeEditorController controller;
  final FocusNode? focusNode;
  final LinuxDoRepository? repository;
  final int? topicId;
  final String placeholder;
  final VoidCallback? onSubmitShortcut;
  final ValueChanged<String>? onCommittedChanged;
  final ValueChanged<String>? onStatusMessage;
  final bool autofocus;

  const LinDoMarkdownWorkbench({
    super.key,
    required this.controller,
    this.focusNode,
    this.repository,
    this.topicId,
    this.placeholder = '',
    this.onSubmitShortcut,
    this.onCommittedChanged,
    this.onStatusMessage,
    this.autofocus = false,
  });

  @override
  State<LinDoMarkdownWorkbench> createState() => _LinDoMarkdownWorkbenchState();
}

class _LinDoMarkdownWorkbenchState extends State<LinDoMarkdownWorkbench> {
  _WorkbenchViewMode _viewMode = _WorkbenchViewMode.edit;
  bool _uploadingAttachment = false;
  String _cachedPreviewRaw = '';
  String _cachedCookedHtml = '';

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onControllerUpdated);
  }

  @override
  void didUpdateWidget(covariant LinDoMarkdownWorkbench oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller != widget.controller) {
      oldWidget.controller.removeListener(_onControllerUpdated);
      widget.controller.addListener(_onControllerUpdated);
    }
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerUpdated);
    super.dispose();
  }

  void _onControllerUpdated() {
    if (!mounted) return;
    setState(() {});
  }

  String _computeCookedPreview() {
    final raw = widget.controller.text;
    if (raw == _cachedPreviewRaw) return _cachedCookedHtml;
    _cachedPreviewRaw = raw;
    _cachedCookedHtml = renderMarkdownToCookedHtml(raw);
    return _cachedCookedHtml;
  }

  Future<void> _handlePickAndUploadAttachment() async {
    final repo = widget.repository;
    if (repo == null || _uploadingAttachment) {
      widget.onStatusMessage?.call(
        LinDoComposerCopy.FILE_PICKER_UNAVAILABLE_HINT.text,
      );
      return;
    }

    final picked = await LinDoPlatformBridge.instance.pickAttachmentFile();
    if (!mounted || picked == null) return;

    final fileName = (picked['fileName'] as String?) ??
        (picked['name'] as String?) ??
        'attachment';
    var filePath =
        (picked['filePath'] as String?) ?? (picked['path'] as String?) ?? '';

    File? tempWrittenFile;
    try {
      if (filePath.trim().isEmpty) {
        Uint8List? rawBytes;
        final b = picked['bytes'];
        final b64 = picked['base64Data'];
        if (b is Uint8List) {
          rawBytes = b;
        } else if (b64 is String && b64.isNotEmpty) {
          rawBytes = base64Decode(b64);
        }
        if (rawBytes == null || rawBytes.isEmpty) {
          widget.onStatusMessage
              ?.call(LinDoMessagesCopy.CANNOT_READ_ATTACHMENT.text);
          return;
        }
        tempWrittenFile = File(
          '${Directory.systemTemp.path}${Platform.pathSeparator}lindo_upload_${DateTime.now().millisecondsSinceEpoch}_$fileName',
        );
        await tempWrittenFile.writeAsBytes(rawBytes, flush: true);
        filePath = tempWrittenFile.path;
      }

      setState(() => _uploadingAttachment = true);
      widget.onStatusMessage?.call(
        LinDoComposerCopy.UPLOADING_FILE.format(fileName),
      );

      final markdownSnippet = await repo.uploadAttachment(
        filePath: filePath,
        fileName: fileName,
      );
      if (!mounted) return;
      if (markdownSnippet.trim().isNotEmpty) {
        final prefix = widget.controller.text.isNotEmpty &&
                !widget.controller.text.endsWith('\n')
            ? '\n'
            : '';
        widget.controller.insertText('$prefix$markdownSnippet\n');
        widget.onStatusMessage?.call(
          LinDoComposerCopy.UPLOADED_INSERTED.format(fileName),
        );
      }
    } catch (err) {
      if (mounted) {
        widget.onStatusMessage?.call(
          err.toString().trim().isNotEmpty
              ? err.toString()
              : LinDoComposerCopy.UPLOAD_FAILED_RETRY.text,
        );
      }
    } finally {
      if (tempWrittenFile != null) {
        try {
          await tempWrittenFile.delete();
        } catch (_) {}
      }
      if (mounted) setState(() => _uploadingAttachment = false);
    }
  }

  void _applyPanguSpacing() {
    if (widget.controller.isComposing) return;
    final original = widget.controller.text;
    if (original.trim().isEmpty) return;

    final cjkAscii = RegExp(
      r'([\u4e00-\u9fff\u3040-\u30ff])([A-Za-z0-9])',
    );
    final asciiCjk = RegExp(
      r'([A-Za-z0-9])([\u4e00-\u9fff\u3040-\u30ff])',
    );

    // Protect fenced code blocks from spacing modifications
    final fenced = RegExp(r'```[\s\S]*?```');
    final savedBlocks = <String>[];
    var working = original.replaceAllMapped(fenced, (m) {
      final idx = savedBlocks.length;
      savedBlocks.add(m.group(0)!);
      return '\u0000CODE_$idx\u0000';
    });

    working = working
        .replaceAllMapped(cjkAscii, (m) => '${m.group(1)} ${m.group(2)}')
        .replaceAllMapped(asciiCjk, (m) => '${m.group(1)} ${m.group(2)}');

    for (var i = savedBlocks.length - 1; i >= 0; i--) {
      working = working.replaceAll('\u0000CODE_$i\u0000', savedBlocks[i]);
    }

    if (working == original) {
      widget.onStatusMessage?.call(LinDoComposerCopy.PANGU_ALREADY_CLEAN.text);
    } else {
      widget.controller.setCommittedTextExternal(working, collapseToEnd: false);
      widget.onStatusMessage?.call(LinDoComposerCopy.PANGU_OPTIMIZED.text);
    }
  }

  Future<void> _openLinkDialog() async {
    final snippet = await showDialog<String>(
      context: context,
      builder: (ctx) => _WorkbenchLinkDialog(repository: widget.repository),
    );
    if (!mounted || snippet == null || snippet.isEmpty) return;
    widget.controller.insertText(snippet);
  }

  Future<void> _openTableDialog() async {
    final snippet = await showDialog<String>(
      context: context,
      builder: (ctx) => const _WorkbenchTableDialog(),
    );
    if (!mounted || snippet == null || snippet.isEmpty) return;
    widget.controller.insertText(snippet);
  }

  Future<void> _openPollDialog() async {
    final snippet = await showDialog<String>(
      context: context,
      builder: (ctx) => const _WorkbenchPollDialog(),
    );
    if (!mounted || snippet == null || snippet.isEmpty) return;
    widget.controller.insertText(snippet);
  }

  Future<void> _openDetailsSpoilerDialog() async {
    final res = await showDialog<({bool isSpoiler, String summary})>(
      context: context,
      builder: (ctx) => const _WorkbenchDetailsSpoilerDialog(),
    );
    if (!mounted || res == null) return;
    if (res.isSpoiler) {
      widget.controller.wrapSelection(
        '[spoiler]',
        '[/spoiler]',
        placeholder: LinDoComposerCopy.TOOL_SPOILER_BLUR.text,
      );
    } else {
      final summary = res.summary.trim().isEmpty
          ? LinDoComposerCopy.DEFAULT_DETAILS_SUMMARY.text
          : res.summary.trim();
      widget.controller.wrapSelection(
        '[details="$summary"]\n',
        '\n[/details]',
        placeholder: LinDoComposerCopy.PLACEHOLDER_WRITE_BODY.text,
      );
    }
  }

  Future<void> _openMentionPicker() async {
    final username = await showDialog<String>(
      context: context,
      builder: (ctx) => _WorkbenchMentionDialog(repository: widget.repository),
    );
    if (!mounted || username == null || username.isEmpty) return;
    widget.controller.insertText('@$username ');
  }

  Future<void> _openEmojiPicker() async {
    final shortcode = await showDialog<String>(
      context: context,
      builder: (ctx) => const _WorkbenchEmojiDialog(),
    );
    if (!mounted || shortcode == null || shortcode.isEmpty) return;
    widget.controller.insertText(':$shortcode: ');
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return LayoutBuilder(
      builder: (context, constraints) {
        final canSplit = constraints.maxWidth >= 660;
        final effectiveMode = (!canSplit && _viewMode == _WorkbenchViewMode.split)
            ? _WorkbenchViewMode.edit
            : _viewMode;

        Widget editorPane = LinDoImeSafeTextField(
          controller: widget.controller,
          focusNode: widget.focusNode,
          hintText: widget.placeholder,
          expands: true,
          autofocus: widget.autofocus,
          onSubmitShortcut: widget.onSubmitShortcut,
          onInsertLinkShortcut: _openLinkDialog,
          onCommittedChanged: widget.onCommittedChanged,
          contentPadding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
        );

        Widget previewPane = Container(
          color: palette.groupedCanvas.withValues(alpha: 0.55),
          child: widget.controller.text.trim().isEmpty
              ? Center(
                  child: Text(
                    LinDoComposerCopy.EMPTY_PREVIEW.text,
                    style: TextStyle(
                      color: palette.inkTertiary,
                      fontSize: scope.sp(13.5),
                    ),
                  ),
                )
              : SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: PostRichContentView(
                    cookedHtml: _computeCookedPreview(),
                    postId: 0,
                  ),
                ),
        );

        Widget mainArea;
        switch (effectiveMode) {
          case _WorkbenchViewMode.edit:
            mainArea = editorPane;
            break;
          case _WorkbenchViewMode.preview:
            mainArea = previewPane;
            break;
          case _WorkbenchViewMode.split:
            mainArea = Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Expanded(child: editorPane),
                VerticalDivider(width: 1, thickness: 0.8, color: palette.hairline),
                Expanded(child: previewPane),
              ],
            );
            break;
        }

        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _buildDockToolbar(context, canSplit: canSplit, activeMode: effectiveMode),
            Divider(height: 1, thickness: 0.75, color: palette.hairline),
            Expanded(child: mainArea),
          ],
        );
      },
    );
  }

  Widget _buildDockToolbar(
    BuildContext context, {
    required bool canSplit,
    required _WorkbenchViewMode activeMode,
  }) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    Widget toolBtn({
      required IconData icon,
      required String tooltip,
      required VoidCallback? onTap,
      bool active = false,
    }) {
      return Tooltip(
        message: tooltip,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(8),
          child: Container(
            width: 32,
            height: 32,
            decoration: active
                ? BoxDecoration(
                    color: palette.activeChipBg,
                    borderRadius: BorderRadius.circular(8),
                  )
                : null,
            alignment: Alignment.center,
            child: Icon(
              icon,
              size: 17,
              color: onTap == null
                  ? palette.inkTertiary.withValues(alpha: 0.42)
                  : (active ? palette.activeChipText : palette.inkSecondary),
            ),
          ),
        ),
      );
    }

    return Container(
      height: 42,
      color: palette.subtleSurface.withValues(alpha: 0.52),
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Row(
        children: [
          Expanded(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  PopupMenuButton<String>(
                    tooltip: LinDoComposerCopy.PANEL_HEADINGS.text,
                    padding: EdgeInsets.zero,
                    onSelected: (prefix) => widget.controller.wrapSelection(
                      prefix,
                      '',
                      placeholder: LinDoComposerCopy.PANEL_HEADINGS.text,
                    ),
                    itemBuilder: (ctx) => [
                      PopupMenuItem(
                        value: '# ',
                        child: Text('H1 · ${LinDoComposerCopy.HEADING_H1.text}'),
                      ),
                      PopupMenuItem(
                        value: '## ',
                        child: Text('H2 · ${LinDoComposerCopy.HEADING_H2.text}'),
                      ),
                      PopupMenuItem(
                        value: '### ',
                        child: Text('H3 · ${LinDoComposerCopy.HEADING_H3.text}'),
                      ),
                      PopupMenuItem(
                        value: '#### ',
                        child: Text('H4 · ${LinDoComposerCopy.HEADING_H4.text}'),
                      ),
                    ],
                    child: Container(
                      width: 32,
                      height: 32,
                      alignment: Alignment.center,
                      child: Icon(
                        LinDoIcons.Heading,
                        size: 17,
                        color: palette.inkSecondary,
                      ),
                    ),
                  ),
                  toolBtn(
                    icon: LinDoIcons.Bold,
                    tooltip: LinDoComposerCopy.TOOLBAR_BOLD.text,
                    onTap: () => widget.controller.wrapSelection(
                      '**',
                      '**',
                      placeholder: LinDoComposerCopy.TOOLBAR_BOLD.text,
                    ),
                  ),
                  toolBtn(
                    icon: LinDoIcons.Italic,
                    tooltip: LinDoComposerCopy.TOOL_ITALIC.text,
                    onTap: () => widget.controller.wrapSelection(
                      '*',
                      '*',
                      placeholder: LinDoComposerCopy.TOOL_ITALIC.text,
                    ),
                  ),
                  toolBtn(
                    icon: LinDoIcons.Strikethrough,
                    tooltip: LinDoComposerCopy.TOOL_STRIKETHROUGH.text,
                    onTap: () => widget.controller.wrapSelection(
                      '~~',
                      '~~',
                      placeholder: LinDoComposerCopy.TOOL_STRIKETHROUGH.text,
                    ),
                  ),
                  PopupMenuButton<String>(
                    tooltip: LinDoComposerCopy.TOOLBAR_CODE_BLOCK.text,
                    padding: EdgeInsets.zero,
                    onSelected: (mode) {
                      if (mode == '_inline_') {
                        widget.controller.wrapSelection(
                          '`',
                          '`',
                          placeholder: 'code',
                        );
                      } else {
                        final lang = mode == '_plain_' ? '' : mode;
                        widget.controller.wrapSelection(
                          '\n```$lang\n',
                          '\n```\n',
                          placeholder: '// code',
                        );
                      }
                    },
                    itemBuilder: (ctx) => [
                      PopupMenuItem(
                        value: '_inline_',
                        child: Text(LinDoComposerCopy.TOOL_INLINE_CODE.text),
                      ),
                      PopupMenuItem(
                        value: '_plain_',
                        child: Text(LinDoComposerCopy.GENERIC_CODE_BLOCK.text),
                      ),
                      for (final lang in const [
                        'python',
                        'bash',
                        'javascript',
                        'typescript',
                        'dart',
                        'rust',
                        'go',
                        'json',
                      ])
                        PopupMenuItem(value: lang, child: Text('```$lang')),
                    ],
                    child: Container(
                      width: 32,
                      height: 32,
                      alignment: Alignment.center,
                      child: Icon(
                        LinDoIcons.Code,
                        size: 17,
                        color: palette.inkSecondary,
                      ),
                    ),
                  ),
                  toolBtn(
                    icon: LinDoIcons.Quote,
                    tooltip: LinDoComposerCopy.TOOL_QUOTE_BLOCK.text,
                    onTap: () => widget.controller.wrapSelection(
                      '[quote]\n',
                      '\n[/quote]\n',
                      placeholder: LinDoComposerCopy.TOOL_QUOTE_BLOCK.text,
                    ),
                  ),
                  toolBtn(
                    icon: LinDoIcons.ListBullet,
                    tooltip: LinDoComposerCopy.TOOL_BULLET_LIST.text,
                    onTap: () => widget.controller.insertText('\n- '),
                  ),
                  toolBtn(
                    icon: LinDoIcons.ListTodo,
                    tooltip: LinDoComposerCopy.TOOL_TASK_LIST.text,
                    onTap: () => widget.controller.insertText('\n- [ ] '),
                  ),
                  toolBtn(
                    icon: LinDoIcons.EyeOff,
                    tooltip: LinDoComposerCopy.PANEL_DETAILS_AND_SPOILER.text,
                    onTap: _openDetailsSpoilerDialog,
                  ),
                  PopupMenuButton<String>(
                    tooltip: LinDoComposerCopy.PANEL_CALLOUT.text,
                    padding: EdgeInsets.zero,
                    onSelected: (alertTag) => widget.controller.wrapSelection(
                      '\n> [!$alertTag]\n> ',
                      '\n',
                      placeholder: LinDoComposerCopy.PLACEHOLDER_WRITE_BODY.text,
                    ),
                    itemBuilder: (ctx) => [
                      PopupMenuItem(
                        value: 'NOTE',
                        child: Text(LinDoComposerCopy.CALLOUT_NOTE.text),
                      ),
                      PopupMenuItem(
                        value: 'TIP',
                        child: Text(LinDoComposerCopy.CALLOUT_TIP.text),
                      ),
                      PopupMenuItem(
                        value: 'IMPORTANT',
                        child: Text(LinDoComposerCopy.CALLOUT_IMPORTANT.text),
                      ),
                      PopupMenuItem(
                        value: 'WARNING',
                        child: Text(LinDoComposerCopy.CALLOUT_WARNING.text),
                      ),
                      PopupMenuItem(
                        value: 'CAUTION',
                        child: Text(LinDoComposerCopy.CALLOUT_CAUTION.text),
                      ),
                    ],
                    child: Container(
                      width: 32,
                      height: 32,
                      alignment: Alignment.center,
                      child: Icon(
                        LinDoIcons.InfoCircle,
                        size: 17,
                        color: palette.inkSecondary,
                      ),
                    ),
                  ),
                  toolBtn(
                    icon: LinDoIcons.Link,
                    tooltip: LinDoComposerCopy.TOOLBAR_LINK.text,
                    onTap: _openLinkDialog,
                  ),
                  toolBtn(
                    icon: LinDoIcons.Table,
                    tooltip: LinDoComposerCopy.PANEL_INSERT_TABLE.text,
                    onTap: _openTableDialog,
                  ),
                  toolBtn(
                    icon: LinDoIcons.Poll,
                    tooltip: LinDoComposerCopy.PANEL_CREATE_POLL.text,
                    onTap: _openPollDialog,
                  ),
                  toolBtn(
                    icon: _uploadingAttachment
                        ? LinDoIcons.Refresh
                        : LinDoIcons.Image,
                    tooltip: LinDoComposerCopy.TOOLBAR_IMAGE.text,
                    onTap: _uploadingAttachment
                        ? null
                        : _handlePickAndUploadAttachment,
                  ),
                  toolBtn(
                    icon: LinDoIcons.AtSign,
                    tooltip: LinDoComposerCopy.TOOLBAR_MENTION.text,
                    onTap: _openMentionPicker,
                  ),
                  toolBtn(
                    icon: LinDoIcons.Smile,
                    tooltip: LinDoComposerCopy.TOOLBAR_EMOJI.text,
                    onTap: _openEmojiPicker,
                  ),
                  toolBtn(
                    icon: LinDoIcons.Sparkles,
                    tooltip: LinDoComposerCopy.TOOL_PANGU_SPACE.text,
                    onTap: _applyPanguSpacing,
                  ),
                  toolBtn(
                    icon: LinDoIcons.Undo,
                    tooltip: LinDoComposerCopy.TOOLBAR_UNDO.text,
                    onTap: widget.controller.canUndo
                        ? widget.controller.undo
                        : null,
                  ),
                  toolBtn(
                    icon: LinDoIcons.Redo,
                    tooltip: LinDoComposerCopy.TOOLBAR_REDO.text,
                    onTap: widget.controller.canRedo
                        ? widget.controller.redo
                        : null,
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(width: 6),
          Container(
            padding: const EdgeInsets.all(2),
            decoration: BoxDecoration(
              color: palette.tonalPill,
              borderRadius: BorderRadius.circular(9),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _buildModeTab(
                  label: LinDoCommonCopy.EDIT.text,
                  selected: activeMode == _WorkbenchViewMode.edit,
                  scope: scope,
                  palette: palette,
                  onTap: () => setState(() => _viewMode = _WorkbenchViewMode.edit),
                ),
                _buildModeTab(
                  label: LinDoCommonCopy.PREVIEW.text,
                  selected: activeMode == _WorkbenchViewMode.preview,
                  scope: scope,
                  palette: palette,
                  onTap: () =>
                      setState(() => _viewMode = _WorkbenchViewMode.preview),
                ),
                if (canSplit)
                  _buildModeTab(
                    label: '${LinDoCommonCopy.EDIT.text}/${LinDoCommonCopy.PREVIEW.text}',
                    selected: activeMode == _WorkbenchViewMode.split,
                    scope: scope,
                    palette: palette,
                    onTap: () =>
                        setState(() => _viewMode = _WorkbenchViewMode.split),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildModeTab({
    required String label,
    required bool selected,
    required LinDoThemeScope scope,
    required LinDoThemePalette palette,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color: selected ? palette.cardSurface : Colors.transparent,
          borderRadius: BorderRadius.circular(7),
        ),
        child: Text(
          label,
          style: TextStyle(
            color: selected ? palette.inkPrimary : palette.inkSecondary,
            fontSize: scope.sp(11.5),
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
          ),
        ),
      ),
    );
  }
}

Future<PublishOutcome?> showLinDoComposerSheet(
  BuildContext context, {
  required LinuxDoRepository repository,
  LinDoLocalStore? store,
  LinDoLocalStore? localStore,
  ComposerAction action = ComposerAction.NEW_TOPIC,
  int? topicId,
  String? topicTitle,
  int? postId,
  int? editPostId,
  int? replyToPostNumber,
  String? replyToUsername,
  String? replyToAvatarTemplate,
  String? quotedPostExcerpt,
  String? initialQuote,
  String? initialRaw,
  String? initialTitle,
  int? initialCategoryId,
  int? categoryId,
  List<String>? initialTags,
  List<String>? initialRecipients,
  List<String>? targetUsernames,
  CloudDraftItem? initialDraft,
  int? minBodyLength,
  Future<void> Function()? onStartSiteVerify,
}) {
  final screen = MediaQuery.sizeOf(context);
  final isDesktopModal = screen.width >= 840;

  Widget sheetContent = ComposerSheet(
    repository: repository,
    store: store ?? localStore,
    action: action,
    topicId: topicId,
    topicTitle: topicTitle,
    postId: editPostId ?? postId,
    replyToPostNumber: replyToPostNumber,
    replyToUsername: replyToUsername,
    replyToAvatarTemplate: replyToAvatarTemplate,
    quotedPostExcerpt: quotedPostExcerpt ?? initialQuote,
    initialRaw: initialRaw,
    initialTitle: initialTitle,
    initialCategoryId: initialCategoryId ?? categoryId,
    initialTags: initialTags,
    initialRecipients: initialRecipients ?? targetUsernames,
    initialDraft: initialDraft,
    minBodyLength: minBodyLength,
    onStartSiteVerify: onStartSiteVerify,
    isDesktopModal: isDesktopModal,
  );

  if (isDesktopModal) {
    return showDialog<PublishOutcome>(
      context: context,
      barrierDismissible: false,
      barrierColor: Colors.black.withValues(alpha: 0.46),
      builder: (dialogCtx) => Dialog(
        backgroundColor: Colors.transparent,
        elevation: 0,
        insetPadding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 860, maxHeight: 700),
          child: sheetContent,
        ),
      ),
    );
  }

  return showModalBottomSheet<PublishOutcome>(
    context: context,
    isScrollControlled: true,
    useSafeArea: true,
    enableDrag: false,
    backgroundColor: Colors.transparent,
    builder: (sheetCtx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(sheetCtx).bottom),
      child: FractionallySizedBox(
        heightFactor: 0.92,
        child: sheetContent,
      ),
    ),
  );
}

class ComposerSheet extends StatefulWidget {
  final LinuxDoRepository repository;
  final LinDoLocalStore? store;
  final ComposerAction action;
  final int? topicId;
  final String? topicTitle;
  final int? postId;
  final int? replyToPostNumber;
  final String? replyToUsername;
  final String? replyToAvatarTemplate;
  final String? quotedPostExcerpt;
  final String? initialRaw;
  final String? initialTitle;
  final int? initialCategoryId;
  final List<String>? initialTags;
  final List<String>? initialRecipients;
  final CloudDraftItem? initialDraft;
  final int? minBodyLength;
  final Future<void> Function()? onStartSiteVerify;
  final bool isDesktopModal;

  const ComposerSheet({
    super.key,
    required this.repository,
    this.store,
    this.action = ComposerAction.NEW_TOPIC,
    this.topicId,
    this.topicTitle,
    this.postId,
    this.replyToPostNumber,
    this.replyToUsername,
    this.replyToAvatarTemplate,
    this.quotedPostExcerpt,
    this.initialRaw,
    this.initialTitle,
    this.initialCategoryId,
    this.initialTags,
    this.initialRecipients,
    this.initialDraft,
    this.minBodyLength,
    this.onStartSiteVerify,
    this.isDesktopModal = false,
  });

  @override
  State<ComposerSheet> createState() => _ComposerSheetState();
}

class _ComposerSheetState extends State<ComposerSheet> {
  late final LinDoLocalStore _effectiveStore =
      widget.store ?? widget.repository.store;

  late final LinDoImeSafeEditorController _titleController;
  late final LinDoImeSafeEditorController _bodyController;
  final FocusNode _titleFocusNode = FocusNode();
  final FocusNode _bodyFocusNode = FocusNode();
  final TextEditingController _recipientInputController =
      TextEditingController();

  int? _selectedCategoryId;
  final List<String> _selectedTags = <String>[];
  final List<String> _recipients = <String>[];

  late final String _draftKey;
  int _draftSequence = 0;
  Timer? _autoSaveDebounce;

  bool _loadingInitialRaw = false;
  bool _submitting = false;
  bool _quoteExpanded = false;
  String? _statusMessage;
  String? _errorBanner;

  bool get _needsTitle =>
      widget.action == ComposerAction.NEW_TOPIC ||
      widget.action == ComposerAction.EDIT_TOPIC ||
      widget.action == ComposerAction.PRIVATE_MESSAGE;

  bool get _needsCategoryAndTags =>
      widget.action == ComposerAction.NEW_TOPIC ||
      widget.action == ComposerAction.EDIT_TOPIC;

  bool get _needsRecipients => widget.action == ComposerAction.PRIVATE_MESSAGE;

  @override
  void initState() {
    super.initState();
    _draftKey = _buildDraftKey();
    final savedLocal =
        widget.initialDraft ?? _effectiveStore.getLocalDraft(_draftKey);
    _draftSequence = savedLocal?.sequence ?? 0;

    final seedTitle = widget.initialTitle ??
        savedLocal?.title ??
        (widget.action == ComposerAction.EDIT_TOPIC
            ? (widget.topicTitle ?? '')
            : '');
    final seedRaw = widget.initialRaw ?? savedLocal?.raw ?? '';

    _titleController = LinDoImeSafeEditorController(initialText: seedTitle);
    _bodyController = LinDoImeSafeEditorController(initialText: seedRaw);

    _selectedCategoryId = widget.initialCategoryId ??
        savedLocal?.categoryId ??
        (widget.repository.categories.isNotEmpty
            ? widget.repository.categories.first.id
            : 4);

    final initTags = widget.initialTags ?? savedLocal?.tags ?? const <String>[];
    for (final t in initTags) {
      if (t.trim().isNotEmpty && !_selectedTags.contains(t.trim())) {
        _selectedTags.add(t.trim());
      }
    }

    final initRecips =
        widget.initialRecipients ?? savedLocal?.recipients ?? const <String>[];
    for (final r in initRecips) {
      final clean = r.trim().replaceFirst(RegExp(r'^@+'), '');
      if (clean.isNotEmpty && !_recipients.contains(clean)) {
        _recipients.add(clean);
      }
    }

    // Apply category template for brand-new empty topic
    if (widget.action == ComposerAction.NEW_TOPIC &&
        seedRaw.trim().isEmpty &&
        _selectedCategoryId != null) {
      final cat = _findCategory(_selectedCategoryId!);
      if (cat != null && cat.defaultTemplate.trim().isNotEmpty) {
        _bodyController.setCommittedTextExternal(cat.defaultTemplate);
      }
    }

    // Load original raw markdown when editing a post/topic if not pre-supplied
    final editTargetId = widget.postId ?? savedLocal?.editPostId;
    if ((widget.action == ComposerAction.EDIT_POST ||
            widget.action == ComposerAction.EDIT_TOPIC) &&
        seedRaw.trim().isEmpty &&
        editTargetId != null &&
        editTargetId > 0) {
      _fetchOriginalRawForEdit(editTargetId);
    }

    _titleController.addListener(_onEditorStateChanged);
    _bodyController.addListener(_onEditorStateChanged);
  }

  String _buildDraftKey() {
    if (widget.initialDraft != null &&
        widget.initialDraft!.draftKey.trim().isNotEmpty) {
      return widget.initialDraft!.draftKey.trim();
    }
    switch (widget.action) {
      case ComposerAction.NEW_TOPIC:
        return 'new_topic';
      case ComposerAction.PRIVATE_MESSAGE:
        return 'new_private_message';
      case ComposerAction.REPLY:
        return 'topic_${widget.topicId ?? 0}';
      case ComposerAction.EDIT_POST:
      case ComposerAction.EDIT_TOPIC:
        return 'edit_post_${widget.postId ?? 0}';
    }
  }

  String get _wireDraftAction {
    switch (widget.action) {
      case ComposerAction.NEW_TOPIC:
        return 'createTopic';
      case ComposerAction.PRIVATE_MESSAGE:
        return 'privateMessage';
      case ComposerAction.EDIT_POST:
      case ComposerAction.EDIT_TOPIC:
        return 'edit';
      case ComposerAction.REPLY:
        return 'reply';
    }
  }

  Future<void> _fetchOriginalRawForEdit(int postId) async {
    setState(() => _loadingInitialRaw = true);
    try {
      final fetched = await widget.repository.fetchPostRaw(
        postId,
        topicId: widget.topicId,
        postNumber: widget.replyToPostNumber,
      );
      if (!mounted) return;
      if (fetched.isNotEmpty && _bodyController.text.trim().isEmpty) {
        _bodyController.setCommittedTextExternal(fetched);
      }
    } catch (_) {
      if (mounted) {
        setState(() {
          _errorBanner = LinDoComposerCopy.CANNOT_READ_RAW_POST_RETRY.text;
        });
      }
    } finally {
      if (mounted) setState(() => _loadingInitialRaw = false);
    }
  }

  void _onEditorStateChanged() {
    if (!mounted) return;
    setState(() {});
    _scheduleDraftAutoSave();
  }

  void _scheduleDraftAutoSave() {
    if (_titleController.isComposing || _bodyController.isComposing) return;
    _autoSaveDebounce?.cancel();
    _autoSaveDebounce = Timer(const Duration(milliseconds: 1600), () {
      _persistDraftNow(silent: true);
    });
  }

  Future<void> _persistDraftNow({required bool silent}) async {
    if (_titleController.isComposing || _bodyController.isComposing) return;
    final raw = _bodyController.text;
    final title = _titleController.text.trim();
    if (raw.trim().isEmpty && title.isEmpty) return;

    try {
      final seq = await widget.repository.saveCloudDraft(
        draftKey: _draftKey,
        sequence: _draftSequence,
        raw: raw,
        title: title,
        categoryId: _selectedCategoryId,
        tags: List<String>.unmodifiable(_selectedTags),
        topicId: widget.topicId,
        replyToPostNumber: widget.replyToPostNumber,
        editPostId: widget.postId,
        recipients: List<String>.unmodifiable(_recipients),
        action: _wireDraftAction,
      );
      _draftSequence = seq;
    } catch (_) {
      await _effectiveStore.saveLocalDraft(
        CloudDraftItem(
          draftKey: _draftKey,
          sequence: _draftSequence,
          action: _wireDraftAction,
          topicId: widget.topicId,
          categoryId: _selectedCategoryId,
          replyToPostNumber: widget.replyToPostNumber,
          editPostId: widget.postId,
          title: title,
          raw: raw,
          tags: List<String>.unmodifiable(_selectedTags),
          recipients: List<String>.unmodifiable(_recipients),
          isLocalOnly: true,
        ),
      );
      if (mounted && !silent) {
        setState(() {
          _statusMessage = LinDoComposerCopy.DRAFT_SAVED_LOCAL_CLOUD_FAILED.text;
        });
      }
    }
  }

  CategoryInfo? _findCategory(int id) {
    for (final c in widget.repository.categories) {
      if (c.id == id) return c;
    }
    return null;
  }

  int get _minRequiredTitleChars =>
      widget.action == ComposerAction.PRIVATE_MESSAGE ? 2 : 5;

  int get _minRequiredBodyChars =>
      widget.minBodyLength ??
      (widget.action == ComposerAction.NEW_TOPIC ? 10 : 5);

  bool get _canSubmitNow {
    if (_submitting || _loadingInitialRaw) return false;
    final bodyLen = Characters(_bodyController.text.trim()).length;
    if (bodyLen < _minRequiredBodyChars) return false;
    if (_needsTitle) {
      final titleLen = Characters(_titleController.text.trim()).length;
      if (titleLen < _minRequiredTitleChars) return false;
    }
    if (_needsRecipients &&
        _recipients.isEmpty &&
        _recipientInputController.text.trim().isEmpty) {
      return false;
    }
    return true;
  }

  void _commitPendingRecipientInput() {
    final raw = _recipientInputController.text.trim();
    if (raw.isEmpty) return;
    for (final part in raw.split(RegExp(r'[,，\s]+'))) {
      final clean = part.trim().replaceFirst(RegExp(r'^@+'), '');
      if (clean.isNotEmpty && !_recipients.contains(clean)) {
        _recipients.add(clean);
      }
    }
    _recipientInputController.clear();
  }

  Future<void> _handleSubmit() async {
    if (_titleController.isComposing || _bodyController.isComposing) return;
    _commitPendingRecipientInput();
    if (!_canSubmitNow) return;

    setState(() {
      _submitting = true;
      _errorBanner = null;
    });

    try {
      final raw = _bodyController.text.trim();
      final title = _titleController.text.trim();
      late PublishOutcome outcome;

      switch (widget.action) {
        case ComposerAction.NEW_TOPIC:
          outcome = await widget.repository.publishTopic(
            title: title,
            raw: raw,
            categoryId: _selectedCategoryId,
            tags: _selectedTags,
          );
          break;
        case ComposerAction.REPLY:
          final tId = widget.topicId ?? 0;
          if (tId <= 0) {
            throw LinuxDoException(LinDoComposerCopy.TOPIC_META_INCOMPLETE.text);
          }
          outcome = await widget.repository.publishReply(
            topicId: tId,
            raw: raw,
            replyToPostNumber: widget.replyToPostNumber,
          );
          break;
        case ComposerAction.EDIT_POST:
          final pId = widget.postId ?? widget.initialDraft?.editPostId ?? 0;
          if (pId <= 0) {
            throw LinuxDoException(LinDoComposerCopy.POST_META_INCOMPLETE.text);
          }
          outcome = await widget.repository.editPost(
            postId: pId,
            raw: raw,
            topicId: widget.topicId,
          );
          break;
        case ComposerAction.EDIT_TOPIC:
          final pId = widget.postId ?? widget.initialDraft?.editPostId ?? 0;
          if (pId <= 0) {
            throw LinuxDoException(LinDoComposerCopy.POST_META_INCOMPLETE.text);
          }
          outcome = await widget.repository.editPost(
            postId: pId,
            raw: raw,
            topicId: widget.topicId,
            newTitle: title,
            categoryId: _selectedCategoryId,
            tags: _selectedTags,
          );
          break;
        case ComposerAction.PRIVATE_MESSAGE:
          if (_recipients.isEmpty) {
            throw LinuxDoException(LinDoNetworkCopy.PM_MISSING_RECIPIENT.text);
          }
          outcome = await widget.repository.sendPrivateMessage(
            targetUsernames: _recipients,
            title: title,
            raw: raw,
          );
          break;
      }

      if (!mounted) return;
      if (outcome.success) {
        _autoSaveDebounce?.cancel();
        await _effectiveStore.deleteLocalDraft(_draftKey);
        try {
          await widget.repository.deleteCloudDraft(
            _draftKey,
            sequence: _draftSequence,
          );
        } catch (_) {}
        if (!mounted) return;
        Navigator.of(context).pop(outcome);
      } else {
        setState(() {
          _errorBanner = outcome.errorMessage ??
              LinDoComposerCopy.SUBMIT_FAILED_KEPT.text;
        });
      }
    } catch (err) {
      await _persistDraftNow(silent: true);
      if (!mounted) return;
      setState(() {
        _errorBanner = err.toString().trim().isNotEmpty
            ? err.toString()
            : LinDoComposerCopy.SUBMIT_FAILED_KEPT.text;
      });
    } finally {
      if (mounted) setState(() => _submitting = false);
    }
  }

  Future<void> _handleClosePressed() async {
    if (_submitting) return;
    if (_titleController.isWithinPostComposingEscapeGrace ||
        _bodyController.isWithinPostComposingEscapeGrace) {
      return;
    }

    final hasUnsavedContent = _bodyController.text.trim().isNotEmpty ||
        (_needsTitle && _titleController.text.trim().isNotEmpty);

    if (!hasUnsavedContent) {
      Navigator.of(context).pop();
      return;
    }

    final choice = await showDialog<String>(
      context: context,
      builder: (ctx) {
        final scope = LinDoThemeScope.of(ctx);
        final palette = scope.palette;
        return AlertDialog(
          backgroundColor: palette.cardSurface,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(18),
          ),
          title: Text(
            LinDoComposerCopy.SAVE_DRAFT_PROMPT.text,
            style: TextStyle(
              color: palette.inkPrimary,
              fontSize: scope.sp(16.0),
              fontWeight: FontWeight.w700,
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(ctx).pop('cancel'),
              child: Text(LinDoCommonCopy.CONTINUE_EDIT.text),
            ),
            TextButton(
              onPressed: () => Navigator.of(ctx).pop('discard'),
              child: Text(
                LinDoCommonCopy.DONT_SAVE.text,
                style: const TextStyle(color: Color(0xFFEF4444)),
              ),
            ),
            FilledButton(
              onPressed: () => Navigator.of(ctx).pop('save'),
              style: FilledButton.styleFrom(
                backgroundColor: palette.publishBtnBg,
                foregroundColor: palette.publishBtnContent,
              ),
              child: Text(LinDoCommonCopy.SAVE.text),
            ),
          ],
        );
      },
    );

    if (!mounted || choice == null || choice == 'cancel') return;
    if (choice == 'save') {
      await _persistDraftNow(silent: true);
    } else if (choice == 'discard') {
      _autoSaveDebounce?.cancel();
      await _effectiveStore.deleteLocalDraft(_draftKey);
      try {
        await widget.repository.deleteCloudDraft(
          _draftKey,
          sequence: _draftSequence,
        );
      } catch (_) {}
    }
    if (mounted) Navigator.of(context).pop();
  }

  Future<void> _openCategoryPicker() async {
    final categories = widget.repository.categories.isNotEmpty
        ? widget.repository.categories
        : _effectiveStore.cachedCategories;
    if (categories.isEmpty) return;

    final chosen = await showDialog<CategoryInfo>(
      context: context,
      builder: (ctx) => _CategoryPickerDialog(
        categories: categories,
        selectedId: _selectedCategoryId,
      ),
    );
    if (!mounted || chosen == null) return;
    setState(() {
      _selectedCategoryId = chosen.id;
      if (_bodyController.text.trim().isEmpty &&
          chosen.defaultTemplate.trim().isNotEmpty) {
        _bodyController.setCommittedTextExternal(chosen.defaultTemplate);
      }
    });
    _scheduleDraftAutoSave();
  }

  Future<void> _openTagPicker() async {
    final currentCategory =
        _selectedCategoryId != null ? _findCategory(_selectedCategoryId!) : null;
    final updated = await showDialog<List<String>>(
      context: context,
      builder: (ctx) => _TagPickerDialog(
        selectedTags: _selectedTags,
        allowedTags: currentCategory?.allowedTags ?? const <String>[],
      ),
    );
    if (!mounted || updated == null) return;
    setState(() {
      _selectedTags
        ..clear()
        ..addAll(updated);
    });
    _scheduleDraftAutoSave();
  }

  Future<void> _openRecipientMentionPicker() async {
    final chosen = await showDialog<String>(
      context: context,
      builder: (ctx) => _WorkbenchMentionDialog(repository: widget.repository),
    );
    if (!mounted || chosen == null || chosen.isEmpty) return;
    if (!_recipients.contains(chosen)) {
      setState(() => _recipients.add(chosen));
      _scheduleDraftAutoSave();
    }
  }

  String _resolveHeaderTitle() {
    switch (widget.action) {
      case ComposerAction.NEW_TOPIC:
        return LinDoComposerCopy.HEADER_NEW_TOPIC.text;
      case ComposerAction.EDIT_TOPIC:
        return LinDoComposerCopy.HEADER_EDIT_TOPIC.text;
      case ComposerAction.EDIT_POST:
        if (widget.replyToPostNumber != null && widget.replyToPostNumber! > 1) {
          return LinDoComposerCopy.HEADER_EDIT_REPLY_FLOOR
              .format(widget.replyToPostNumber!);
        }
        return LinDoComposerCopy.HEADER_EDIT_POST.text;
      case ComposerAction.REPLY:
        if (widget.replyToPostNumber != null && widget.replyToPostNumber! > 0) {
          return LinDoComposerCopy.REPLY_FLOOR_NUMBER
              .format(widget.replyToPostNumber!);
        }
        return LinDoComposerCopy.HEADER_REPLY_TOPIC.text;
      case ComposerAction.PRIVATE_MESSAGE:
        return LinDoMessagesCopy.PM_SHEET_TITLE.text;
    }
  }

  String _resolveBodyPlaceholder() {
    switch (widget.action) {
      case ComposerAction.NEW_TOPIC:
        return LinDoComposerCopy.PLACEHOLDER_WRITE_BODY.text;
      case ComposerAction.EDIT_TOPIC:
      case ComposerAction.EDIT_POST:
        return LinDoComposerCopy.PLACEHOLDER_EDIT_BODY.text;
      case ComposerAction.REPLY:
        return LinDoComposerCopy.PLACEHOLDER_WRITE_REPLY.text;
      case ComposerAction.PRIVATE_MESSAGE:
        return LinDoMessagesCopy.PM_BODY_PLACEHOLDER.text;
    }
  }

  String _resolveSubmitLabel() {
    switch (widget.action) {
      case ComposerAction.PRIVATE_MESSAGE:
        return LinDoCommonCopy.SEND.text;
      case ComposerAction.EDIT_POST:
      case ComposerAction.EDIT_TOPIC:
        return LinDoCommonCopy.SAVE.text;
      case ComposerAction.NEW_TOPIC:
      case ComposerAction.REPLY:
        return LinDoCommonCopy.PUBLISH.text;
    }
  }

  @override
  void dispose() {
    _autoSaveDebounce?.cancel();
    _titleController.removeListener(_onEditorStateChanged);
    _bodyController.removeListener(_onEditorStateChanged);
    _titleController.dispose();
    _bodyController.dispose();
    _titleFocusNode.dispose();
    _bodyFocusNode.dispose();
    _recipientInputController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final canPopSafely = !_submitting &&
        !_titleController.isWithinPostComposingEscapeGrace &&
        !_bodyController.isWithinPostComposingEscapeGrace;

    final selectedCategory =
        _selectedCategoryId != null ? _findCategory(_selectedCategoryId!) : null;
    final bodyChars = Characters(_bodyController.text.trim()).length;
    final titleChars = Characters(_titleController.text.trim()).length;

    String lengthHint;
    if (_needsTitle && titleChars < _minRequiredTitleChars) {
      lengthHint = LinDoComposerCopy.TITLE_CHARS_REMAINING
          .format(_minRequiredTitleChars - titleChars);
    } else if (bodyChars < _minRequiredBodyChars) {
      lengthHint = LinDoComposerCopy.BODY_CHARS_REMAINING
          .format(_minRequiredBodyChars - bodyChars);
    } else {
      lengthHint = '$bodyChars';
    }

    return PopScope(
      canPop: canPopSafely,
      child: Container(
        decoration: BoxDecoration(
          color: palette.cardSurface,
          borderRadius: widget.isDesktopModal
              ? BorderRadius.circular(22)
              : const BorderRadius.vertical(top: Radius.circular(22)),
          border: Border.all(color: palette.hairline, width: 0.9),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: palette.isDark ? 0.44 : 0.14),
              blurRadius: 28,
              offset: const Offset(0, 12),
            ),
          ],
        ),
        clipBehavior: Clip.antiAlias,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _buildHeaderBar(scope, palette),
            Divider(height: 1, thickness: 0.8, color: palette.hairline),
            if (_errorBanner != null) _buildErrorBanner(scope, palette),
            if (_needsRecipients) _buildRecipientsBar(scope, palette),
            if (_needsCategoryAndTags)
              _buildCategoryAndTagsBar(scope, palette, selectedCategory),
            if (_needsTitle) _buildTitleInput(scope, palette),
            if (widget.action == ComposerAction.REPLY &&
                (widget.replyToUsername != null ||
                    (widget.quotedPostExcerpt ?? '').trim().isNotEmpty))
              _buildReplyQuoteChip(scope, palette),
            Expanded(
              child: _loadingInitialRaw
                  ? Center(
                      child: LinDoStatePanel(
                        mood: MascotMood.connecting,
                        title: LinDoCommonCopy.LOADING.text,
                        compact: true,
                      ),
                    )
                  : LinDoMarkdownWorkbench(
                      controller: _bodyController,
                      focusNode: _bodyFocusNode,
                      repository: widget.repository,
                      topicId: widget.topicId,
                      placeholder: _resolveBodyPlaceholder(),
                      autofocus: !_needsTitle,
                      onSubmitShortcut: _handleSubmit,
                      onStatusMessage: (msg) {
                        if (mounted) setState(() => _statusMessage = msg);
                      },
                    ),
            ),
            Divider(height: 1, thickness: 0.8, color: palette.hairline),
            _buildFooterBar(scope, palette, lengthHint),
          ],
        ),
      ),
    );
  }

  Widget _buildHeaderBar(LinDoThemeScope scope, LinDoThemePalette palette) {
    return SizedBox(
      height: 52,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12),
        child: Row(
          children: [
            IconButton(
              onPressed: _handleClosePressed,
              tooltip: LinDoCommonCopy.CLOSE.text,
              icon: Icon(
                LinDoIcons.Close,
                size: 20,
                color: palette.inkSecondary,
              ),
            ),
            const SizedBox(width: 4),
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _resolveHeaderTitle(),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: palette.inkPrimary,
                      fontSize: scope.sp(15.5),
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  if ((widget.topicTitle ?? '').trim().isNotEmpty &&
                      widget.action == ComposerAction.REPLY)
                    Text(
                      widget.topicTitle!.trim(),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.inkTertiary,
                        fontSize: scope.sp(11.5),
                      ),
                    ),
                ],
              ),
            ),
            SizedBox(
              height: 36,
              child: FilledButton.icon(
                onPressed: _canSubmitNow ? _handleSubmit : null,
                style: FilledButton.styleFrom(
                  backgroundColor: palette.publishBtnBg,
                  foregroundColor: palette.publishBtnContent,
                  elevation: 0,
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(11),
                  ),
                ),
                icon: Icon(
                  widget.action == ComposerAction.PRIVATE_MESSAGE
                      ? LinDoIcons.Send
                      : LinDoIcons.Check,
                  size: 15,
                ),
                label: Text(
                  _submitting
                      ? LinDoCommonCopy.SUBMITTING.text
                      : _resolveSubmitLabel(),
                  style: TextStyle(
                    fontSize: scope.sp(13.0),
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildErrorBanner(LinDoThemeScope scope, LinDoThemePalette palette) {
    final isVerify = _errorBanner.isSiteVerificationError;
    return Container(
      color: palette.isDark ? const Color(0xFF3A1D20) : const Color(0xFFFEF2F2),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          const Icon(LinDoIcons.Warning, size: 16, color: Color(0xFFEF4444)),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              _errorBanner!,
              style: TextStyle(
                color: palette.isDark
                    ? const Color(0xFFFCA5A5)
                    : const Color(0xFFB91C1C),
                fontSize: scope.sp(12.5),
              ),
            ),
          ),
          if (isVerify && widget.onStartSiteVerify != null)
            TextButton(
              onPressed: () async {
                await widget.onStartSiteVerify!();
                if (mounted) setState(() => _errorBanner = null);
              },
              child: Text(LinDoCommonCopy.GO_VERIFY.text),
            ),
        ],
      ),
    );
  }

  Widget _buildRecipientsBar(LinDoThemeScope scope, LinDoThemePalette palette) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(color: palette.hairline, width: 0.7)),
      ),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Icon(LinDoIcons.User, size: 15, color: palette.inkTertiary),
          for (final user in _recipients)
            InputChip(
              label: Text(
                LinDoCommonCopy.AT_USERNAME.format(user),
                style: TextStyle(
                  color: palette.activeChipText,
                  fontSize: scope.sp(12.0),
                  fontWeight: FontWeight.w600,
                ),
              ),
              backgroundColor: palette.activeChipBg,
              visualDensity: VisualDensity.compact,
              onDeleted: () {
                setState(() => _recipients.remove(user));
                _scheduleDraftAutoSave();
              },
            ),
          SizedBox(
            width: 190,
            child: TextField(
              controller: _recipientInputController,
              style: TextStyle(
                color: palette.inkPrimary,
                fontSize: scope.sp(13.0),
              ),
              onSubmitted: (_) => setState(_commitPendingRecipientInput),
              decoration: InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: LinDoMessagesCopy.PM_RECIPIENTS_PLACEHOLDER.text,
                hintStyle: TextStyle(
                  color: palette.inkTertiary,
                  fontSize: scope.sp(12.5),
                ),
              ),
            ),
          ),
          IconButton(
            onPressed: _openRecipientMentionPicker,
            tooltip: LinDoComposerCopy.PANEL_MENTION_USER.text,
            visualDensity: VisualDensity.compact,
            icon: Icon(LinDoIcons.Plus, size: 17, color: palette.logoDoColor),
          ),
        ],
      ),
    );
  }

  Widget _buildCategoryAndTagsBar(
    LinDoThemeScope scope,
    LinDoThemePalette palette,
    CategoryInfo? selectedCategory,
  ) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 9),
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(color: palette.hairline, width: 0.7)),
      ),
      child: Wrap(
        spacing: 8,
        runSpacing: 6,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          CategoryMorandiChip(
            categoryName:
                selectedCategory?.name ?? LinDoComposerCopy.SELECT_CATEGORY.text,
            colorHex: selectedCategory?.colorHex,
            selected: selectedCategory != null,
            onTap: _openCategoryPicker,
          ),
          InkWell(
            onTap: _openTagPicker,
            borderRadius: BorderRadius.circular(9),
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
              decoration: BoxDecoration(
                color: palette.tonalPill,
                borderRadius: BorderRadius.circular(9),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(LinDoIcons.Tag, size: 13, color: palette.inkSecondary),
                  const SizedBox(width: 4),
                  Text(
                    _selectedTags.isEmpty
                        ? LinDoComposerCopy.SELECT_TAGS.text
                        : LinDoComposerCopy.MANAGE_TAGS_COUNT
                            .format([_selectedTags.length, 5]),
                    style: TextStyle(
                      color: palette.inkSecondary,
                      fontSize: scope.sp(12.0),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
            ),
          ),
          for (final tag in _selectedTags)
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3.5),
              decoration: BoxDecoration(
                color: palette.subtleSurface,
                borderRadius: BorderRadius.circular(7),
                border: Border.all(color: palette.hairline, width: 0.7),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    '#$tag',
                    style: TextStyle(
                      color: palette.inkSecondary,
                      fontSize: scope.sp(11.5),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(width: 4),
                  GestureDetector(
                    onTap: () {
                      setState(() => _selectedTags.remove(tag));
                      _scheduleDraftAutoSave();
                    },
                    child: Icon(
                      LinDoIcons.Close,
                      size: 13,
                      color: palette.inkTertiary,
                    ),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildTitleInput(LinDoThemeScope scope, LinDoThemePalette palette) {
    final hint = widget.action == ComposerAction.PRIVATE_MESSAGE
        ? LinDoMessagesCopy.PM_SUBJECT_PLACEHOLDER.text
        : LinDoComposerCopy.TOPIC_TITLE_PLACEHOLDER.text;
    return Container(
      decoration: BoxDecoration(
        border: Border(bottom: BorderSide(color: palette.hairline, width: 0.7)),
      ),
      child: LinDoImeSafeTextField(
        controller: _titleController,
        focusNode: _titleFocusNode,
        autofocus: true,
        maxLines: 1,
        enableMarkdownShortcuts: false,
        textInputAction: TextInputAction.next,
        onSubmitted: (_) => _bodyFocusNode.requestFocus(),
        onSubmitShortcut: _handleSubmit,
        hintText: hint,
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(16.5),
          fontWeight: FontWeight.w700,
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
      ),
    );
  }

  Widget _buildReplyQuoteChip(LinDoThemeScope scope, LinDoThemePalette palette) {
    final excerpt = (widget.quotedPostExcerpt ?? '').trim();
    return Container(
      margin: const EdgeInsets.fromLTRB(14, 10, 14, 4),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: palette.subtleSurface,
        borderRadius: BorderRadius.circular(11),
        border: Border.all(color: palette.hairline, width: 0.8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              if (widget.replyToUsername != null) ...[
                SmartAvatar(
                  avatarUrl: widget.replyToAvatarTemplate ?? '',
                  fallbackLabel: widget.replyToUsername!,
                  size: 20,
                ),
                const SizedBox(width: 7),
              ],
              Expanded(
                child: Text(
                  [
                    if (widget.replyToUsername != null &&
                        widget.replyToUsername!.isNotEmpty)
                      LinDoCommonCopy.AT_USERNAME
                          .format(widget.replyToUsername!),
                    if (widget.replyToPostNumber != null)
                      LinDoReaderCopy.FLOOR_LABEL
                          .format(widget.replyToPostNumber!),
                  ].join(' · '),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: palette.inkSecondary,
                    fontSize: scope.sp(12.0),
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              if (excerpt.isNotEmpty) ...[
                TextButton.icon(
                  onPressed: () {
                    final userAttr = (widget.replyToUsername ?? '').trim();
                    final postAttr = widget.replyToPostNumber != null
                        ? ', post:${widget.replyToPostNumber}'
                        : '';
                    final topicAttr = widget.topicId != null
                        ? ', topic:${widget.topicId}'
                        : '';
                    final header = userAttr.isNotEmpty
                        ? '[quote="$userAttr$postAttr$topicAttr"]'
                        : '[quote]';
                    _bodyController.insertText('$header\n$excerpt\n[/quote]\n\n');
                  },
                  style: TextButton.styleFrom(
                    visualDensity: VisualDensity.compact,
                    padding: const EdgeInsets.symmetric(horizontal: 8),
                  ),
                  icon: const Icon(LinDoIcons.Quote, size: 14),
                  label: Text(
                    LinDoReaderCopy.QUOTE_THIS_FLOOR_MENU.text,
                    style: TextStyle(fontSize: scope.sp(11.5)),
                  ),
                ),
                InkWell(
                  onTap: () => setState(() => _quoteExpanded = !_quoteExpanded),
                  child: Icon(
                    _quoteExpanded
                        ? LinDoIcons.ChevronUp
                        : LinDoIcons.ChevronDown,
                    size: 16,
                    color: palette.inkTertiary,
                  ),
                ),
              ],
            ],
          ),
          if (_quoteExpanded && excerpt.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              excerpt,
              maxLines: 5,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                color: palette.inkSecondary,
                fontSize: scope.sp(12.5),
                height: 1.45,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildFooterBar(
    LinDoThemeScope scope,
    LinDoThemePalette palette,
    String lengthHint,
  ) {
    return Container(
      height: 38,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      color: palette.subtleSurface.withValues(alpha: 0.42),
      child: Row(
        children: [
          if (_statusMessage != null && _statusMessage!.isNotEmpty)
            Expanded(
              child: Text(
                _statusMessage!,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  color: palette.inkSecondary,
                  fontSize: scope.sp(11.5),
                ),
              ),
            )
          else
            const Spacer(),
          const SizedBox(width: 8),
          Text(
            lengthHint,
            style: TextStyle(
              color: _canSubmitNow ? palette.inkTertiary : palette.logoDoColor,
              fontSize: scope.sp(11.5),
              fontWeight: FontWeight.w600,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

class _WorkbenchLinkDialog extends StatefulWidget {
  final LinuxDoRepository? repository;
  const _WorkbenchLinkDialog({required this.repository});

  @override
  State<_WorkbenchLinkDialog> createState() => _WorkbenchLinkDialogState();
}

class _WorkbenchLinkDialogState extends State<_WorkbenchLinkDialog> {
  WorkbenchLinkTab _tab = WorkbenchLinkTab.EXTERNAL_URL;
  final TextEditingController _urlCtrl =
      TextEditingController(text: 'https://');
  final TextEditingController _labelCtrl = TextEditingController();
  final TextEditingController _searchCtrl = TextEditingController();
  List<CommunitySearchResult> _results = const <CommunitySearchResult>[];
  bool _searching = false;

  @override
  void dispose() {
    _urlCtrl.dispose();
    _labelCtrl.dispose();
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _runSearch(String query) async {
    final repo = widget.repository;
    if (repo == null || query.trim().isEmpty) return;
    setState(() => _searching = true);
    try {
      final res = await repo.searchTopicsAndPosts(query.trim());
      if (mounted) setState(() => _results = res.items);
    } catch (_) {
      if (mounted) setState(() => _results = const <CommunitySearchResult>[]);
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      title: Text(
        LinDoComposerCopy.PANEL_INSERT_LINK.text,
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(16.0),
          fontWeight: FontWeight.w700,
        ),
      ),
      content: SizedBox(
        width: 400,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (widget.repository != null) ...[
              Row(
                children: WorkbenchLinkTab.values.map((t) {
                  final active = _tab == t;
                  return Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 3),
                      child: ChoiceChip(
                        label: Text(t.labelCopy.text),
                        selected: active,
                        onSelected: (_) => setState(() => _tab = t),
                      ),
                    ),
                  );
                }).toList(),
              ),
              const SizedBox(height: 12),
            ],
            if (_tab == WorkbenchLinkTab.EXTERNAL_URL) ...[
              TextField(
                controller: _urlCtrl,
                autofocus: true,
                decoration: InputDecoration(
                  labelText: LinDoComposerCopy.LINK_URL_LABEL.text,
                  isDense: true,
                ),
              ),
              const SizedBox(height: 10),
              TextField(
                controller: _labelCtrl,
                decoration: InputDecoration(
                  labelText: LinDoComposerCopy.LINK_TEXT_LABEL.text,
                  hintText:
                      LinDoComposerCopy.LINK_TEXT_OPTIONAL_PLACEHOLDER.text,
                  isDense: true,
                ),
              ),
            ] else ...[
              TextField(
                controller: _searchCtrl,
                autofocus: true,
                onSubmitted: _runSearch,
                decoration: InputDecoration(
                  hintText: LinDoComposerCopy.SEARCH_TOPICS_PLACEHOLDER.text,
                  suffixIcon: IconButton(
                    icon: const Icon(LinDoIcons.Search, size: 17),
                    onPressed: () => _runSearch(_searchCtrl.text),
                  ),
                  isDense: true,
                ),
              ),
              const SizedBox(height: 10),
              SizedBox(
                height: 190,
                child: _searching
                    ? const Center(child: CircularProgressIndicator())
                    : _results.isEmpty
                        ? Center(
                            child: Text(
                              LinDoComposerCopy.NO_MATCHING_POSTS.text,
                              style: TextStyle(
                                color: palette.inkTertiary,
                                fontSize: scope.sp(12.5),
                              ),
                            ),
                          )
                        : ListView.builder(
                            itemCount: _results.length,
                            itemBuilder: (ctx, idx) {
                              final item = _results[idx];
                              return ListTile(
                                dense: true,
                                title: Text(
                                  item.title,
                                  maxLines: 1,
                                  overflow: TextOverflow.ellipsis,
                                ),
                                subtitle: Text('@${item.username}'),
                                onTap: () => Navigator.of(context).pop(
                                  '[${item.title}](https://linux.do/t/topic/${item.topicId}/${item.postNumber})',
                                ),
                              );
                            },
                          ),
              ),
            ],
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(LinDoCommonCopy.CANCEL.text),
        ),
        if (_tab == WorkbenchLinkTab.EXTERNAL_URL)
          FilledButton(
            onPressed: () {
              final url = _urlCtrl.text.trim();
              if (!url.startsWith('http://') && !url.startsWith('https://')) {
                return;
              }
              final text = _labelCtrl.text.trim().isEmpty
                  ? url
                  : _labelCtrl.text.trim();
              Navigator.of(context).pop('[$text]($url)');
            },
            style: FilledButton.styleFrom(
              backgroundColor: palette.publishBtnBg,
              foregroundColor: palette.publishBtnContent,
            ),
            child: Text(LinDoCommonCopy.INSERT.text),
          ),
      ],
    );
  }
}

class _WorkbenchTableDialog extends StatefulWidget {
  const _WorkbenchTableDialog();

  @override
  State<_WorkbenchTableDialog> createState() => _WorkbenchTableDialogState();
}

class _WorkbenchTableDialogState extends State<_WorkbenchTableDialog> {
  int _cols = 3;
  int _rows = 3;

  String _generateTableMarkdown() {
    final headers = List.generate(_cols, (i) => '列 ${i + 1}').join(' | ');
    final sep = List.generate(_cols, (_) => '---').join(' | ');
    final bodyRows = List.generate(
      _rows,
      (_) => List.generate(_cols, (_) => '内容').join(' | '),
    ).join('\n| ');
    return '\n| $headers |\n| $sep |\n| $bodyRows |\n';
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    Widget stepperRow(String label, int val, ValueChanged<int> onChanged,
        {int min = 2, int max = 8}) {
      return Row(
        children: [
          Expanded(
            child: Text(
              label,
              style: TextStyle(
                color: palette.inkPrimary,
                fontSize: scope.sp(14.0),
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          IconButton(
            onPressed: val > min ? () => onChanged(val - 1) : null,
            icon: const Icon(LinDoIcons.Minus, size: 18),
          ),
          Text(
            '$val',
            style: TextStyle(
              color: palette.inkPrimary,
              fontSize: scope.sp(15.0),
              fontWeight: FontWeight.w700,
            ),
          ),
          IconButton(
            onPressed: val < max ? () => onChanged(val + 1) : null,
            icon: const Icon(LinDoIcons.Plus, size: 18),
          ),
        ],
      );
    }

    return AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      title: Text(
        LinDoComposerCopy.PANEL_INSERT_TABLE.text,
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(16.0),
          fontWeight: FontWeight.w700,
        ),
      ),
      content: SizedBox(
        width: 320,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            stepperRow(
              LinDoComposerCopy.TABLE_COLS_LABEL.text,
              _cols,
              (v) => setState(() => _cols = v),
              max: 6,
            ),
            stepperRow(
              LinDoComposerCopy.TABLE_ROWS_LABEL.text,
              _rows,
              (v) => setState(() => _rows = v),
              max: 10,
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(LinDoCommonCopy.CANCEL.text),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_generateTableMarkdown()),
          style: FilledButton.styleFrom(
            backgroundColor: palette.publishBtnBg,
            foregroundColor: palette.publishBtnContent,
          ),
          child: Text(
            LinDoComposerCopy.INSERT_TABLE_DIM_BTN.format([_cols, _rows]),
          ),
        ),
      ],
    );
  }
}

class _WorkbenchPollDialog extends StatefulWidget {
  const _WorkbenchPollDialog();

  @override
  State<_WorkbenchPollDialog> createState() => _WorkbenchPollDialogState();
}

class _WorkbenchPollDialogState extends State<_WorkbenchPollDialog> {
  WorkbenchPollType _type = WorkbenchPollType.REGULAR;
  WorkbenchPollResultsVisibility _results =
      WorkbenchPollResultsVisibility.ALWAYS;
  bool _publicVoters = true;
  final TextEditingController _titleCtrl = TextEditingController();
  final TextEditingController _optionsCtrl = TextEditingController(
    text: LinDoComposerCopy.POLL_DEFAULT_OPTIONS.text,
  );
  int _min = 1;
  int _max = 2;

  @override
  void dispose() {
    _titleCtrl.dispose();
    _optionsCtrl.dispose();
    super.dispose();
  }

  String? _buildPollBBCode() {
    final attrs = <String>[
      'type=${_type.wireType}',
      'results=${_results.wireValue}',
      'public=${_publicVoters ? "true" : "false"}',
    ];
    if (_type == WorkbenchPollType.MULTIPLE) {
      attrs.add('min=$_min');
      attrs.add('max=${math.max(_min, _max)}');
    } else if (_type == WorkbenchPollType.NUMBER) {
      attrs.add('min=1');
      attrs.add('max=10');
      attrs.add('step=1');
    }

    final title = _titleCtrl.text.trim();
    final titleLine = title.isNotEmpty ? '# $title\n' : '';

    if (_type == WorkbenchPollType.NUMBER) {
      return '\n[poll ${attrs.join(' ')}]\n$titleLine[/poll]\n';
    }

    final lines = _optionsCtrl.text
        .split('\n')
        .map((s) => s.trim())
        .where((s) => s.isNotEmpty)
        .toList();
    if (lines.length < 2) return null;
    final items = lines.map((s) => '* $s').join('\n');
    return '\n[poll ${attrs.join(' ')}]\n$titleLine$items\n[/poll]\n';
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      title: Text(
        LinDoComposerCopy.PANEL_CREATE_POLL.text,
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(16.0),
          fontWeight: FontWeight.w700,
        ),
      ),
      content: SizedBox(
        width: 420,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Wrap(
                spacing: 6,
                children: WorkbenchPollType.values.map((t) {
                  return ChoiceChip(
                    label: Text(t.labelCopy.text),
                    selected: _type == t,
                    onSelected: (_) => setState(() => _type = t),
                  );
                }).toList(),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 6,
                children: WorkbenchPollResultsVisibility.values.map((r) {
                  return ChoiceChip(
                    label: Text(r.labelCopy.text),
                    selected: _results == r,
                    onSelected: (_) => setState(() => _results = r),
                  );
                }).toList(),
              ),
              SwitchListTile.adaptive(
                contentPadding: EdgeInsets.zero,
                dense: true,
                title: Text(
                  _publicVoters
                      ? LinDoComposerCopy.POLL_PUBLIC_VOTERS.text
                      : LinDoComposerCopy.POLL_ANONYMOUS_VOTERS.text,
                  style: TextStyle(fontSize: scope.sp(13.0)),
                ),
                value: _publicVoters,
                onChanged: (v) => setState(() => _publicVoters = v),
              ),
              TextField(
                controller: _titleCtrl,
                decoration: InputDecoration(
                  hintText: LinDoComposerCopy.POLL_TITLE_PLACEHOLDER.text,
                  isDense: true,
                ),
              ),
              if (_type == WorkbenchPollType.MULTIPLE) ...[
                const SizedBox(height: 8),
                Row(
                  children: [
                    Text('${LinDoComposerCopy.POLL_MIN_LABEL.text}: $_min'),
                    IconButton(
                      onPressed: _min > 1 ? () => setState(() => _min--) : null,
                      icon: const Icon(LinDoIcons.Minus, size: 16),
                    ),
                    IconButton(
                      onPressed: () => setState(() {
                        _min++;
                        if (_max < _min) _max = _min;
                      }),
                      icon: const Icon(LinDoIcons.Plus, size: 16),
                    ),
                    const Spacer(),
                    Text('${LinDoComposerCopy.POLL_MAX_LABEL.text}: $_max'),
                    IconButton(
                      onPressed:
                          _max > _min ? () => setState(() => _max--) : null,
                      icon: const Icon(LinDoIcons.Minus, size: 16),
                    ),
                    IconButton(
                      onPressed: () => setState(() => _max++),
                      icon: const Icon(LinDoIcons.Plus, size: 16),
                    ),
                  ],
                ),
              ],
              if (_type != WorkbenchPollType.NUMBER) ...[
                const SizedBox(height: 10),
                TextField(
                  controller: _optionsCtrl,
                  minLines: 3,
                  maxLines: 6,
                  decoration: InputDecoration(
                    hintText: LinDoComposerCopy.POLL_OPTIONS_PLACEHOLDER.text,
                    border: const OutlineInputBorder(),
                    isDense: true,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(LinDoCommonCopy.CANCEL.text),
        ),
        FilledButton(
          onPressed: () {
            final bbcode = _buildPollBBCode();
            if (bbcode != null) Navigator.of(context).pop(bbcode);
          },
          style: FilledButton.styleFrom(
            backgroundColor: palette.publishBtnBg,
            foregroundColor: palette.publishBtnContent,
          ),
          child: Text(LinDoComposerCopy.INSERT_POLL_BTN.text),
        ),
      ],
    );
  }
}

class _WorkbenchDetailsSpoilerDialog extends StatefulWidget {
  const _WorkbenchDetailsSpoilerDialog();

  @override
  State<_WorkbenchDetailsSpoilerDialog> createState() =>
      _WorkbenchDetailsSpoilerDialogState();
}

class _WorkbenchDetailsSpoilerDialogState
    extends State<_WorkbenchDetailsSpoilerDialog> {
  final TextEditingController _summaryCtrl = TextEditingController();

  @override
  void dispose() {
    _summaryCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      title: Text(
        LinDoComposerCopy.PANEL_DETAILS_AND_SPOILER.text,
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(16.0),
          fontWeight: FontWeight.w700,
        ),
      ),
      content: SizedBox(
        width: 340,
        child: TextField(
          controller: _summaryCtrl,
          decoration: InputDecoration(
            hintText: LinDoComposerCopy.DETAILS_SUMMARY_PLACEHOLDER.text,
            isDense: true,
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(LinDoCommonCopy.CANCEL.text),
        ),
        OutlinedButton(
          onPressed: () => Navigator.of(context).pop(
            (isSpoiler: true, summary: ''),
          ),
          child: Text(LinDoComposerCopy.INSERT_SPOILER_BTN.text),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(
            (isSpoiler: false, summary: _summaryCtrl.text),
          ),
          style: FilledButton.styleFrom(
            backgroundColor: palette.publishBtnBg,
            foregroundColor: palette.publishBtnContent,
          ),
          child: Text(LinDoComposerCopy.INSERT_DETAILS_BTN.text),
        ),
      ],
    );
  }
}

class _WorkbenchMentionDialog extends StatefulWidget {
  final LinuxDoRepository? repository;
  const _WorkbenchMentionDialog({required this.repository});

  @override
  State<_WorkbenchMentionDialog> createState() =>
      _WorkbenchMentionDialogState();
}

class _WorkbenchMentionDialogState extends State<_WorkbenchMentionDialog> {
  final TextEditingController _queryCtrl = TextEditingController();
  Timer? _debounce;
  List<TopicUser> _users = const <TopicUser>[];
  bool _loading = false;

  @override
  void dispose() {
    _debounce?.cancel();
    _queryCtrl.dispose();
    super.dispose();
  }

  void _onQueryChanged(String q) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 260), () async {
      final repo = widget.repository;
      if (repo == null || q.trim().isEmpty) {
        if (mounted) setState(() => _users = const <TopicUser>[]);
        return;
      }
      setState(() => _loading = true);
      try {
        final found = await repo.searchUsersForMention(q.trim());
        if (mounted) setState(() => _users = found);
      } catch (_) {
        if (mounted) setState(() => _users = const <TopicUser>[]);
      } finally {
        if (mounted) setState(() => _loading = false);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      title: Text(
        LinDoComposerCopy.PANEL_MENTION_USER.text,
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(16.0),
          fontWeight: FontWeight.w700,
        ),
      ),
      content: SizedBox(
        width: 360,
        height: 300,
        child: Column(
          children: [
            TextField(
              controller: _queryCtrl,
              autofocus: true,
              onChanged: _onQueryChanged,
              onSubmitted: (v) {
                final clean = v.trim().replaceFirst(RegExp(r'^@+'), '');
                if (clean.isNotEmpty) Navigator.of(context).pop(clean);
              },
              decoration: InputDecoration(
                prefixIcon: const Icon(LinDoIcons.AtSign, size: 16),
                hintText: LinDoComposerCopy.SEARCH_USERS_PLACEHOLDER.text,
                isDense: true,
              ),
            ),
            const SizedBox(height: 10),
            Expanded(
              child: _loading
                  ? const Center(child: CircularProgressIndicator())
                  : _users.isEmpty
                      ? Center(
                          child: Text(
                            LinDoComposerCopy.NO_MATCHING_USERS.text,
                            style: TextStyle(
                              color: palette.inkTertiary,
                              fontSize: scope.sp(12.5),
                            ),
                          ),
                        )
                      : ListView.builder(
                          itemCount: _users.length,
                          itemBuilder: (ctx, idx) {
                            final u = _users[idx];
                            return ListTile(
                              dense: true,
                              leading: SmartAvatar(
                                avatarUrl: u.avatarTemplate,
                                fallbackLabel: u.username,
                                size: 30,
                              ),
                              title: Text('@${u.username}'),
                              subtitle: (u.name != null && u.name!.isNotEmpty)
                                  ? Text(u.name!)
                                  : null,
                              onTap: () => Navigator.of(context).pop(u.username),
                            );
                          },
                        ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(LinDoCommonCopy.CANCEL.text),
        ),
      ],
    );
  }
}

class _WorkbenchEmojiDialog extends StatefulWidget {
  const _WorkbenchEmojiDialog();

  @override
  State<_WorkbenchEmojiDialog> createState() => _WorkbenchEmojiDialogState();
}

class _WorkbenchEmojiDialogState extends State<_WorkbenchEmojiDialog> {
  final TextEditingController _searchCtrl = TextEditingController();
  late final Map<String, List<EmojiCatalogItem>> _grouped =
      LinuxDoEmojiCatalog.instance.groupedEmojis;
  late String _selectedGroup =
      _grouped.keys.isNotEmpty ? _grouped.keys.first : 'smileys_&_emotion';
  String _query = '';

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final items = _query.trim().isNotEmpty
        ? LinuxDoEmojiCatalog.instance.search(_query)
        : (_grouped[_selectedGroup] ?? const <EmojiCatalogItem>[]);

    return AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      title: Text(
        LinDoComposerCopy.PANEL_SELECT_EMOJI.text,
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(16.0),
          fontWeight: FontWeight.w700,
        ),
      ),
      content: SizedBox(
        width: 400,
        height: 360,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _searchCtrl,
              autofocus: true,
              onChanged: (v) => setState(() => _query = v),
              decoration: InputDecoration(
                prefixIcon: const Icon(LinDoIcons.Search, size: 16),
                hintText: LinDoComposerCopy.SEARCH_EMOJIS_PLACEHOLDER.text,
                isDense: true,
              ),
            ),
            if (_query.trim().isEmpty && _grouped.isNotEmpty) ...[
              const SizedBox(height: 8),
              SizedBox(
                height: 32,
                child: ListView(
                  scrollDirection: Axis.horizontal,
                  children: _grouped.keys.map((group) {
                    final selected = group == _selectedGroup;
                    return Padding(
                      padding: const EdgeInsets.only(right: 6),
                      child: ChoiceChip(
                        label: Text(
                          group.replaceAll('_', ' '),
                          style: TextStyle(fontSize: scope.sp(11.0)),
                        ),
                        selected: selected,
                        visualDensity: VisualDensity.compact,
                        onSelected: (_) =>
                            setState(() => _selectedGroup = group),
                      ),
                    );
                  }).toList(),
                ),
              ),
            ],
            const SizedBox(height: 8),
            Expanded(
              child: items.isEmpty
                  ? Center(
                      child: Text(
                        LinDoComposerCopy.NO_MATCHING_EMOJIS.text,
                        style: TextStyle(
                          color: palette.inkTertiary,
                          fontSize: scope.sp(12.5),
                        ),
                      ),
                    )
                  : GridView.builder(
                      gridDelegate:
                          const SliverGridDelegateWithMaxCrossAxisExtent(
                        maxCrossAxisExtent: 44,
                        mainAxisSpacing: 4,
                        crossAxisSpacing: 4,
                      ),
                      itemCount: items.length,
                      itemBuilder: (ctx, idx) {
                        final item = items[idx];
                        return Tooltip(
                          message: ':${item.name}:',
                          child: InkWell(
                            onTap: () => Navigator.of(context).pop(item.name),
                            borderRadius: BorderRadius.circular(8),
                            child: Center(
                              child: LinDoNetworkImage(
                                url: item.url,
                                width: 24,
                                height: 24,
                                fit: BoxFit.contain,
                                placeholder: Text(
                                  item.unicodeChar ?? '',
                                  style: const TextStyle(fontSize: 18),
                                ),
                                errorFallback: Text(
                                  item.unicodeChar ?? item.name,
                                  maxLines: 1,
                                  style: TextStyle(
                                    fontSize:
                                        item.unicodeChar != null ? 18 : 9,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(LinDoCommonCopy.CLOSE.text),
        ),
      ],
    );
  }
}

class _CategoryPickerDialog extends StatelessWidget {
  final List<CategoryInfo> categories;
  final int? selectedId;

  const _CategoryPickerDialog({
    required this.categories,
    required this.selectedId,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;

    return AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      title: Text(
        LinDoComposerCopy.PICKER_SELECT_CATEGORY_TITLE.text,
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(16.0),
          fontWeight: FontWeight.w700,
        ),
      ),
      content: SizedBox(
        width: 380,
        height: 360,
        child: ListView.separated(
          itemCount: categories.length,
          separatorBuilder: (_, _) =>
              Divider(height: 1, color: palette.hairline),
          itemBuilder: (ctx, idx) {
            final cat = categories[idx];
            final selected = cat.id == selectedId;
            return ListTile(
              dense: true,
              leading: Container(
                width: 10,
                height: 10,
                decoration: BoxDecoration(
                  color: parseHexColor(cat.colorHex),
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
              title: Text(
                cat.name,
                style: TextStyle(
                  color: palette.inkPrimary,
                  fontSize: scope.sp(13.5),
                  fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                ),
              ),
              subtitle: cat.descriptionText.trim().isNotEmpty
                  ? Text(
                      cat.descriptionText.trim(),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        color: palette.inkTertiary,
                        fontSize: scope.sp(11.5),
                      ),
                    )
                  : null,
              trailing: selected
                  ? Icon(LinDoIcons.Check, size: 17, color: palette.logoDoColor)
                  : null,
              onTap: () => Navigator.of(context).pop(cat),
            );
          },
        ),
      ),
    );
  }
}

class _TagPickerDialog extends StatefulWidget {
  final List<String> selectedTags;
  final List<String> allowedTags;

  const _TagPickerDialog({
    required this.selectedTags,
    required this.allowedTags,
  });

  @override
  State<_TagPickerDialog> createState() => _TagPickerDialogState();
}

class _TagPickerDialogState extends State<_TagPickerDialog> {
  static const List<String> _kCommonTags = <String>[
    '开源',
    '分享',
    '求助',
    'AI',
    'Linux',
    'Flutter',
    'Docker',
    '效率工具',
    '网络',
    '经验总结',
  ];

  late final List<String> _chosen = List<String>.from(widget.selectedTags);
  final TextEditingController _inputCtrl = TextEditingController();

  @override
  void dispose() {
    _inputCtrl.dispose();
    super.dispose();
  }

  void _toggleTag(String tag) {
    final clean = tag.trim().replaceFirst(RegExp(r'^#+'), '');
    if (clean.isEmpty) return;
    setState(() {
      if (_chosen.contains(clean)) {
        _chosen.remove(clean);
      } else if (_chosen.length < 5) {
        _chosen.add(clean);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final pool = widget.allowedTags.isNotEmpty ? widget.allowedTags : _kCommonTags;

    return AlertDialog(
      backgroundColor: palette.cardSurface,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18)),
      title: Text(
        '${LinDoComposerCopy.PICKER_SELECT_TAGS_TITLE.text} (${LinDoComposerCopy.PICKER_SELECTED_TAGS_COUNT.format([_chosen.length, 5])})',
        style: TextStyle(
          color: palette.inkPrimary,
          fontSize: scope.sp(15.5),
          fontWeight: FontWeight.w700,
        ),
      ),
      content: SizedBox(
        width: 380,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            TextField(
              controller: _inputCtrl,
              autofocus: true,
              onSubmitted: (v) {
                _toggleTag(v);
                _inputCtrl.clear();
              },
              decoration: InputDecoration(
                prefixIcon: const Icon(LinDoIcons.Tag, size: 16),
                hintText: LinDoComposerCopy.SEARCH_TAGS_PLACEHOLDER.text,
                suffixIcon: IconButton(
                  icon: const Icon(LinDoIcons.Plus, size: 17),
                  onPressed: () {
                    _toggleTag(_inputCtrl.text);
                    _inputCtrl.clear();
                  },
                ),
                isDense: true,
              ),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: {
                ..._chosen,
                ...pool,
              }.map((tag) {
                final selected = _chosen.contains(tag);
                return FilterChip(
                  label: Text('#$tag'),
                  selected: selected,
                  onSelected: (_) => _toggleTag(tag),
                );
              }).toList(),
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: Text(LinDoCommonCopy.CANCEL.text),
        ),
        FilledButton(
          onPressed: () => Navigator.of(context).pop(_chosen),
          style: FilledButton.styleFrom(
            backgroundColor: palette.publishBtnBg,
            foregroundColor: palette.publishBtnContent,
          ),
          child: Text(LinDoCommonCopy.CONFIRM.text),
        ),
      ],
    );
  }
}
