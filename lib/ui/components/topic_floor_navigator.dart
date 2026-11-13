import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/copy/lindo_copy.dart';
import '../theme/lindo_theme.dart';
import 'lindo_icons.dart';

class TopicFloorNavigatorPill extends StatelessWidget {
  final int currentFloor;
  final int totalFloors;
  final VoidCallback onTap;

  const TopicFloorNavigatorPill({
    super.key,
    required this.currentFloor,
    required this.totalFloors,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final safeTotal = math.max(1, totalFloors);
    final safeCurrent = currentFloor.clamp(1, safeTotal);
    final ratio = safeTotal <= 1
        ? 1.0
        : ((safeCurrent - 1) / (safeTotal - 1)).clamp(0.0, 1.0);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(999),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
          decoration: BoxDecoration(
            color: palette.cardSurface.withValues(alpha: 0.94),
            borderRadius: BorderRadius.circular(999),
            border: Border.all(color: palette.hairline, width: 0.85),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: palette.isDark ? 0.34 : 0.08),
                blurRadius: 12,
                offset: const Offset(0, 4),
              ),
            ],
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              SizedBox(
                width: 14,
                height: 14,
                child: CircularProgressIndicator(
                  value: ratio,
                  strokeWidth: 2.1,
                  backgroundColor: palette.hairline,
                  valueColor: AlwaysStoppedAnimation<Color>(palette.logoDoColor),
                ),
              ),
              const SizedBox(width: 7),
              Text(
                safeTotal <= 1
                    ? LinDoReaderCopy.SINGLE_FLOOR_TEXT.text
                    : LinDoReaderCopy.FLOOR_COUNTER
                        .format([safeCurrent, safeTotal]),
                style: TextStyle(
                  color: palette.inkPrimary,
                  fontSize: scope.sp(12.0),
                  fontWeight: FontWeight.w700,
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
              const SizedBox(width: 3),
              Icon(
                LinDoIcons.ChevronUp,
                size: 14,
                color: palette.inkTertiary,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

Future<int?> showTopicFloorJumpSheet(
  BuildContext context, {
  required int currentFloor,
  required int totalFloors,
}) {
  final size = MediaQuery.sizeOf(context);
  final isWide = size.width >= 680;
  if (isWide) {
    return showDialog<int>(
      context: context,
      barrierColor: Colors.black.withValues(alpha: 0.38),
      builder: (ctx) => Dialog(
        backgroundColor: Colors.transparent,
        elevation: 0,
        insetPadding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: _TopicFloorJumpPanel(
            currentFloor: currentFloor,
            totalFloors: totalFloors,
            isModalDialog: true,
          ),
        ),
      ),
    );
  }

  return showModalBottomSheet<int>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(ctx).bottom),
      child: _TopicFloorJumpPanel(
        currentFloor: currentFloor,
        totalFloors: totalFloors,
        isModalDialog: false,
      ),
    ),
  );
}

class _TopicFloorJumpPanel extends StatefulWidget {
  final int currentFloor;
  final int totalFloors;
  final bool isModalDialog;

  const _TopicFloorJumpPanel({
    required this.currentFloor,
    required this.totalFloors,
    required this.isModalDialog,
  });

  @override
  State<_TopicFloorJumpPanel> createState() => _TopicFloorJumpPanelState();
}

class _TopicFloorJumpPanelState extends State<_TopicFloorJumpPanel> {
  late final int _safeTotal = math.max(1, widget.totalFloors);
  late double _sliderValue =
      widget.currentFloor.clamp(1, _safeTotal).toDouble();
  FloorNavigatorMode _mode = FloorNavigatorMode.SLIDER;
  late final TextEditingController _inputController = TextEditingController(
    text: '${widget.currentFloor.clamp(1, _safeTotal)}',
  );
  final FocusNode _inputFocusNode = FocusNode();

  @override
  void dispose() {
    _inputController.dispose();
    _inputFocusNode.dispose();
    super.dispose();
  }

  void _toggleMode() {
    setState(() {
      if (_mode == FloorNavigatorMode.SLIDER) {
        _mode = FloorNavigatorMode.EXACT_INPUT;
        _inputController.text = '${_sliderValue.round()}';
        _inputController.selection = TextSelection(
          baseOffset: 0,
          extentOffset: _inputController.text.length,
        );
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) _inputFocusNode.requestFocus();
        });
      } else {
        final parsed = int.tryParse(_inputController.text.trim());
        if (parsed != null) {
          _sliderValue = parsed.clamp(1, _safeTotal).toDouble();
        }
        _mode = FloorNavigatorMode.SLIDER;
      }
    });
  }

  void _submitInputFloor() {
    final parsed = int.tryParse(_inputController.text.trim());
    if (parsed == null) return;
    final target = parsed.clamp(1, _safeTotal);
    Navigator.of(context).pop(target);
  }

  @override
  Widget build(BuildContext context) {
    final scope = LinDoThemeScope.of(context);
    final palette = scope.palette;
    final previewFloor = _sliderValue.round().clamp(1, _safeTotal);

    return Container(
      decoration: BoxDecoration(
        color: palette.cardSurface,
        borderRadius: widget.isModalDialog
            ? BorderRadius.circular(20)
            : const BorderRadius.vertical(top: Radius.circular(22)),
        border: Border.all(color: palette.hairline, width: 0.8),
      ),
      padding: EdgeInsets.fromLTRB(
        20,
        widget.isModalDialog ? 16 : 12,
        20,
        widget.isModalDialog
            ? 20
            : math.max(20.0, MediaQuery.paddingOf(context).bottom + 14.0),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (!widget.isModalDialog) ...[
            Center(
              child: Container(
                width: 36,
                height: 4,
                decoration: BoxDecoration(
                  color: palette.hairline,
                  borderRadius: BorderRadius.circular(999),
                ),
              ),
            ),
            const SizedBox(height: 12),
          ],
          Row(
            children: [
              Expanded(
                child: Text(
                  LinDoReaderCopy.FLOOR_COUNTER
                      .format([previewFloor, _safeTotal]),
                  style: TextStyle(
                    color: palette.inkPrimary,
                    fontSize: scope.sp(16.5),
                    fontWeight: FontWeight.w800,
                    fontFeatures: const [FontFeature.tabularFigures()],
                  ),
                ),
              ),
              IconButton(
                onPressed: _toggleMode,
                tooltip: _mode == FloorNavigatorMode.SLIDER
                    ? LinDoReaderCopy.FLOOR_ENTER_NUMBER_DESC.text
                    : LinDoReaderCopy.FLOOR_BACK_TO_SLIDER_DESC.text,
                visualDensity: VisualDensity.compact,
                icon: Icon(
                  _mode == FloorNavigatorMode.SLIDER
                      ? LinDoIcons.Edit
                      : LinDoIcons.Sliders,
                  size: 17,
                  color: palette.inkSecondary,
                ),
              ),
              IconButton(
                onPressed: () => Navigator.of(context).pop(),
                tooltip: LinDoReaderCopy.CLOSE_FLOOR_NAV_DESC.text,
                visualDensity: VisualDensity.compact,
                icon: Icon(
                  LinDoIcons.Close,
                  size: 18,
                  color: palette.inkSecondary,
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () => Navigator.of(context).pop(1),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: palette.inkPrimary,
                    side: BorderSide(color: palette.hairline, width: 0.9),
                    minimumSize: const Size(0, 40),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  icon: const Icon(LinDoIcons.ChevronUp, size: 16),
                  label: Text(
                    LinDoReaderCopy.FIRST_FLOOR.text,
                    style: TextStyle(
                      fontSize: scope.sp(13.0),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () => Navigator.of(context).pop(_safeTotal),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: palette.inkPrimary,
                    side: BorderSide(color: palette.hairline, width: 0.9),
                    minimumSize: const Size(0, 40),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                  icon: const Icon(LinDoIcons.ChevronDown, size: 16),
                  label: Text(
                    LinDoReaderCopy.LAST_FLOOR.text,
                    style: TextStyle(
                      fontSize: scope.sp(13.0),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 14),
          if (_mode == FloorNavigatorMode.SLIDER) ...[
            SliderTheme(
              data: SliderTheme.of(context).copyWith(
                activeTrackColor: palette.logoDoColor,
                inactiveTrackColor: palette.subtleSurface,
                thumbColor: palette.logoDoColor,
                overlayColor: palette.logoDoColor.withValues(alpha: 0.16),
                trackHeight: 5.0,
              ),
              child: Semantics(
                label: LinDoReaderCopy.TARGET_FLOOR_SEMANTICS.text,
                child: Slider(
                  min: 1.0,
                  max: math.max(1.0, _safeTotal.toDouble()),
                  divisions: _safeTotal > 1 ? math.min(200, _safeTotal - 1) : null,
                  value: _sliderValue.clamp(1.0, math.max(1.0, _safeTotal.toDouble())),
                  onChanged: _safeTotal <= 1
                      ? null
                      : (val) => setState(() => _sliderValue = val),
                ),
              ),
            ),
            const SizedBox(height: 8),
            SizedBox(
              height: 44,
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(previewFloor),
                style: FilledButton.styleFrom(
                  backgroundColor: palette.publishBtnBg,
                  foregroundColor: palette.publishBtnContent,
                  elevation: 0,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(13),
                  ),
                ),
                child: Text(
                  '${LinDoReaderCopy.JUMP_ACTION.text} · ${LinDoReaderCopy.FLOOR_LABEL.format(previewFloor)}',
                  style: TextStyle(
                    fontSize: scope.sp(14.0),
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ),
          ] else ...[
            Row(
              children: [
                Expanded(
                  child: Semantics(
                    label: LinDoReaderCopy.FLOOR_NUMBER_SEMANTICS.text,
                    child: TextField(
                      controller: _inputController,
                      focusNode: _inputFocusNode,
                      keyboardType: TextInputType.number,
                      textInputAction: TextInputAction.go,
                      inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                      onSubmitted: (_) => _submitInputFloor(),
                      style: TextStyle(
                        color: palette.inkPrimary,
                        fontSize: scope.sp(15.0),
                        fontWeight: FontWeight.w600,
                      ),
                      decoration: InputDecoration(
                        hintText: LinDoReaderCopy.FLOOR_INPUT_PLACEHOLDER
                            .format(_safeTotal),
                        hintStyle: TextStyle(
                          color: palette.inkTertiary,
                          fontSize: scope.sp(13.5),
                        ),
                        filled: true,
                        fillColor: palette.subtleSurface,
                        isDense: true,
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 14,
                          vertical: 12,
                        ),
                        border: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide(color: palette.hairline, width: 0.8),
                        ),
                        enabledBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide(color: palette.hairline, width: 0.8),
                        ),
                        focusedBorder: OutlineInputBorder(
                          borderRadius: BorderRadius.circular(12),
                          borderSide: BorderSide(
                            color: palette.logoDoColor,
                            width: 1.2,
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 10),
                SizedBox(
                  height: 44,
                  child: FilledButton(
                    onPressed: _submitInputFloor,
                    style: FilledButton.styleFrom(
                      backgroundColor: palette.publishBtnBg,
                      foregroundColor: palette.publishBtnContent,
                      elevation: 0,
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    child: Text(
                      LinDoReaderCopy.GO_TO_FLOOR_ACTION.text,
                      style: TextStyle(
                        fontSize: scope.sp(14.0),
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}
