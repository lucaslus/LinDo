/// 全局统一文案枚举契约：
/// - 所有面向用户的静态文案与参数化模板均收敛至枚举常量，便于后续集中审校、改写或多语言扩展；
/// - 动态参数采用 `{0}`、`{1}`、`{2}` 占位符，通过 [format] 安全替换；
/// - 所有实现枚举均覆盖 `toString() => text`，防止插值误输出枚举常量名。
mixin LinDoCopyEntry {
  String get text;

  String format([Object? a0 = _unset, Object? a1 = _unset, Object? a2 = _unset, Object? a3 = _unset]) {
    final args = <Object?>[a0, a1, a2, a3];
    var resolved = text;
    for (var i = 0; i < args.length; i++) {
      if (!identical(args[i], _unset)) {
        resolved = resolved.replaceAll('{$i}', args[i]?.toString() ?? '');
      }
    }
    return resolved;
  }

  String formatList(List<Object?> args) {
    if (args.isEmpty) return text;
    var resolved = text;
    for (var i = 0; i < args.length; i++) {
      resolved = resolved.replaceAll('{$i}', args[i]?.toString() ?? '');
    }
    return resolved;
  }
}

const Object _unset = Object();
