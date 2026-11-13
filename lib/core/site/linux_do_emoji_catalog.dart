import 'linux_do_site_rules.dart';

class EmojiCatalogItem {
  final String name;
  final String url;
  final String group;
  final String? unicodeChar;

  const EmojiCatalogItem({
    required this.name,
    required this.url,
    this.group = 'default',
    this.unicodeChar,
  });
}

String _twitterEmojiUrl(String name) =>
    '$kLinuxDoBaseUrl/images/emoji/twitter/$name.png?v=12';

class _SeedDef {
  final String name;
  final String unicode;
  final String group;
  const _SeedDef(this.name, this.unicode, this.group);
}

const List<_SeedDef> _kPreseededEmojiDefs = <_SeedDef>[
  // Smileys & Emotion
  _SeedDef('grinning', '😀', 'smileys_&_emotion'),
  _SeedDef('smiley', '😃', 'smileys_&_emotion'),
  _SeedDef('smile', '😄', 'smileys_&_emotion'),
  _SeedDef('grin', '😁', 'smileys_&_emotion'),
  _SeedDef('laughing', '😆', 'smileys_&_emotion'),
  _SeedDef('satisfied', '😆', 'smileys_&_emotion'),
  _SeedDef('sweat_smile', '😅', 'smileys_&_emotion'),
  _SeedDef('rofl', '🤣', 'smileys_&_emotion'),
  _SeedDef('joy', '😂', 'smileys_&_emotion'),
  _SeedDef('slightly_smiling_face', '🙂', 'smileys_&_emotion'),
  _SeedDef('upside_down_face', '🙃', 'smileys_&_emotion'),
  _SeedDef('melting_face', '🫠', 'smileys_&_emotion'),
  _SeedDef('wink', '😉', 'smileys_&_emotion'),
  _SeedDef('blush', '😊', 'smileys_&_emotion'),
  _SeedDef('innocent', '😇', 'smileys_&_emotion'),
  _SeedDef('smiling_face_with_three_hearts', '🥰', 'smileys_&_emotion'),
  _SeedDef('heart_eyes', '😍', 'smileys_&_emotion'),
  _SeedDef('star_struck', '🤩', 'smileys_&_emotion'),
  _SeedDef('kissing_heart', '😘', 'smileys_&_emotion'),
  _SeedDef('kissing', '😗', 'smileys_&_emotion'),
  _SeedDef('kissing_smiling_eyes', '😙', 'smileys_&_emotion'),
  _SeedDef('kissing_closed_eyes', '😚', 'smileys_&_emotion'),
  _SeedDef('yum', '😋', 'smileys_&_emotion'),
  _SeedDef('stuck_out_tongue', '😛', 'smileys_&_emotion'),
  _SeedDef('stuck_out_tongue_winking_eye', '😜', 'smileys_&_emotion'),
  _SeedDef('zany_face', '🤪', 'smileys_&_emotion'),
  _SeedDef('stuck_out_tongue_closed_eyes', '😝', 'smileys_&_emotion'),
  _SeedDef('money_mouth_face', '🤑', 'smileys_&_emotion'),
  _SeedDef('hugs', '🤗', 'smileys_&_emotion'),
  _SeedDef('hand_over_mouth', '🤭', 'smileys_&_emotion'),
  _SeedDef('shushing_face', '🤫', 'smileys_&_emotion'),
  _SeedDef('thinking', '🤔', 'smileys_&_emotion'),
  _SeedDef('saluting_face', '🫡', 'smileys_&_emotion'),
  _SeedDef('zipper_mouth_face', '🤐', 'smileys_&_emotion'),
  _SeedDef('raised_eyebrow', '🤨', 'smileys_&_emotion'),
  _SeedDef('neutral_face', '😐', 'smileys_&_emotion'),
  _SeedDef('expressionless', '😑', 'smileys_&_emotion'),
  _SeedDef('no_mouth', '😶', 'smileys_&_emotion'),
  _SeedDef('smirk', '😏', 'smileys_&_emotion'),
  _SeedDef('unamused', '😒', 'smileys_&_emotion'),
  _SeedDef('roll_eyes', '🙄', 'smileys_&_emotion'),
  _SeedDef('grimacing', '😬', 'smileys_&_emotion'),
  _SeedDef('lying_face', '🤥', 'smileys_&_emotion'),
  _SeedDef('relieved', '😌', 'smileys_&_emotion'),
  _SeedDef('pensive', '😔', 'smileys_&_emotion'),
  _SeedDef('sleepy', '😪', 'smileys_&_emotion'),
  _SeedDef('drooling_face', '🤤', 'smileys_&_emotion'),
  _SeedDef('sleeping', '😴', 'smileys_&_emotion'),
  _SeedDef('mask', '😷', 'smileys_&_emotion'),
  _SeedDef('face_with_thermometer', '🤒', 'smileys_&_emotion'),
  _SeedDef('head_bandage', '🤕', 'smileys_&_emotion'),
  _SeedDef('nauseated_face', '🤢', 'smileys_&_emotion'),
  _SeedDef('vomiting_face', '🤮', 'smileys_&_emotion'),
  _SeedDef('sneezing_face', '🤧', 'smileys_&_emotion'),
  _SeedDef('hot_face', '🥵', 'smileys_&_emotion'),
  _SeedDef('cold_face', '🥶', 'smileys_&_emotion'),
  _SeedDef('woozy_face', '🥴', 'smileys_&_emotion'),
  _SeedDef('dizzy_face', '😵', 'smileys_&_emotion'),
  _SeedDef('exploding_head', '🤯', 'smileys_&_emotion'),
  _SeedDef('cowboy_hat_face', '🤠', 'smileys_&_emotion'),
  _SeedDef('partying_face', '🥳', 'smileys_&_emotion'),
  _SeedDef('disguised_face', '🥸', 'smileys_&_emotion'),
  _SeedDef('sunglasses', '😎', 'smileys_&_emotion'),
  _SeedDef('nerd_face', '🤓', 'smileys_&_emotion'),
  _SeedDef('monocle_face', '🧐', 'smileys_&_emotion'),
  _SeedDef('confused', '😕', 'smileys_&_emotion'),
  _SeedDef('worried', '😟', 'smileys_&_emotion'),
  _SeedDef('slightly_frowning_face', '🙁', 'smileys_&_emotion'),
  _SeedDef('frowning_face', '☹️', 'smileys_&_emotion'),
  _SeedDef('open_mouth', '😮', 'smileys_&_emotion'),
  _SeedDef('hushed', '😯', 'smileys_&_emotion'),
  _SeedDef('astonished', '😲', 'smileys_&_emotion'),
  _SeedDef('flushed', '😳', 'smileys_&_emotion'),
  _SeedDef('pleading_face', '🥺', 'smileys_&_emotion'),
  _SeedDef('holding_back_tears', '🥹', 'smileys_&_emotion'),
  _SeedDef('anguished', '😧', 'smileys_&_emotion'),
  _SeedDef('fearful', '😨', 'smileys_&_emotion'),
  _SeedDef('cold_sweat', '😰', 'smileys_&_emotion'),
  _SeedDef('disappointed_relieved', '😥', 'smileys_&_emotion'),
  _SeedDef('cry', '😢', 'smileys_&_emotion'),
  _SeedDef('sob', '😭', 'smileys_&_emotion'),
  _SeedDef('scream', '😱', 'smileys_&_emotion'),
  _SeedDef('confounded', '😖', 'smileys_&_emotion'),
  _SeedDef('persevere', '😣', 'smileys_&_emotion'),
  _SeedDef('disappointed', '😞', 'smileys_&_emotion'),
  _SeedDef('sweat', '😓', 'smileys_&_emotion'),
  _SeedDef('weary', '😩', 'smileys_&_emotion'),
  _SeedDef('tired_face', '😫', 'smileys_&_emotion'),
  _SeedDef('yawning_face', '🥱', 'smileys_&_emotion'),
  _SeedDef('triumph', '😤', 'smileys_&_emotion'),
  _SeedDef('rage', '😡', 'smileys_&_emotion'),
  _SeedDef('angry', '😠', 'smileys_&_emotion'),
  _SeedDef('cursing_face', '🤬', 'smileys_&_emotion'),
  _SeedDef('smiling_imp', '😈', 'smileys_&_emotion'),
  _SeedDef('skull', '💀', 'smileys_&_emotion'),
  _SeedDef('poop', '💩', 'smileys_&_emotion'),
  _SeedDef('clown_face', '🤡', 'smileys_&_emotion'),
  _SeedDef('ghost', '👻', 'smileys_&_emotion'),
  _SeedDef('alien', '👽', 'smileys_&_emotion'),
  _SeedDef('robot', '🤖', 'smileys_&_emotion'),
  _SeedDef('heart', '❤️', 'smileys_&_emotion'),
  _SeedDef('orange_heart', '🧡', 'smileys_&_emotion'),
  _SeedDef('yellow_heart', '💛', 'smileys_&_emotion'),
  _SeedDef('green_heart', '💚', 'smileys_&_emotion'),
  _SeedDef('blue_heart', '💙', 'smileys_&_emotion'),
  _SeedDef('purple_heart', '💜', 'smileys_&_emotion'),
  _SeedDef('broken_heart', '💔', 'smileys_&_emotion'),
  _SeedDef('sparkling_heart', '💖', 'smileys_&_emotion'),
  _SeedDef('two_hearts', '💕', 'smileys_&_emotion'),
  _SeedDef('heartpulse', '💗', 'smileys_&_emotion'),
  _SeedDef('100', '💯', 'smileys_&_emotion'),
  _SeedDef('boom', '💥', 'smileys_&_emotion'),
  _SeedDef('dizzy', '💫', 'smileys_&_emotion'),
  _SeedDef('zzz', '💤', 'smileys_&_emotion'),

  // People & Body
  _SeedDef('+1', '👍', 'people_&_body'),
  _SeedDef('-1', '👎', 'people_&_body'),
  _SeedDef('thumbsup', '👍', 'people_&_body'),
  _SeedDef('thumbsdown', '👎', 'people_&_body'),
  _SeedDef('clap', '👏', 'people_&_body'),
  _SeedDef('raised_hands', '🙌', 'people_&_body'),
  _SeedDef('pray', '🙏', 'people_&_body'),
  _SeedDef('handshake', '🤝', 'people_&_body'),
  _SeedDef('muscle', '💪', 'people_&_body'),
  _SeedDef('wave', '👋', 'people_&_body'),
  _SeedDef('ok_hand', '👌', 'people_&_body'),
  _SeedDef('pinched_fingers', '🤌', 'people_&_body'),
  _SeedDef('v', '✌️', 'people_&_body'),
  _SeedDef('crossed_fingers', '🤞', 'people_&_body'),
  _SeedDef('love_you_gesture', '🤟', 'people_&_body'),
  _SeedDef('metal', '🤘', 'people_&_body'),
  _SeedDef('call_me_hand', '🤙', 'people_&_body'),
  _SeedDef('point_left', '👈', 'people_&_body'),
  _SeedDef('point_right', '👉', 'people_&_body'),
  _SeedDef('point_up', '☝️', 'people_&_body'),
  _SeedDef('point_up_2', '👆', 'people_&_body'),
  _SeedDef('point_down', '👇', 'people_&_body'),
  _SeedDef('fist', '✊', 'people_&_body'),
  _SeedDef('punch', '👊', 'people_&_body'),
  _SeedDef('writing_hand', '✍️', 'people_&_body'),
  _SeedDef('eyes', '👀', 'people_&_body'),
  _SeedDef('brain', '🧠', 'people_&_body'),
  _SeedDef('man_shrugging', '🤷‍♂️', 'people_&_body'),
  _SeedDef('woman_shrugging', '🤷‍♀️', 'people_&_body'),
  _SeedDef('shrug', '🤷', 'people_&_body'),
  _SeedDef('facepalm', '🤦', 'people_&_body'),

  // Animals & Nature
  _SeedDef('dog', '🐶', 'animals_&_nature'),
  _SeedDef('cat', '🐱', 'animals_&_nature'),
  _SeedDef('fox_face', '🦊', 'animals_&_nature'),
  _SeedDef('panda_face', '🐼', 'animals_&_nature'),
  _SeedDef('koala', '🐨', 'animals_&_nature'),
  _SeedDef('unicorn', '🦄', 'animals_&_nature'),
  _SeedDef('penguin', '🐧', 'animals_&_nature'),
  _SeedDef('whale', '🐳', 'animals_&_nature'),
  _SeedDef('dolphin', '🐬', 'animals_&_nature'),
  _SeedDef('octopus', '🐙', 'animals_&_nature'),
  _SeedDef('bug', '🐛', 'animals_&_nature'),
  _SeedDef('butterfly', '🦋', 'animals_&_nature'),
  _SeedDef('bee', '🐝', 'animals_&_nature'),
  _SeedDef('turtle', '🐢', 'animals_&_nature'),
  _SeedDef('dragon', '🐉', 'animals_&_nature'),
  _SeedDef('seedling', '🌱', 'animals_&_nature'),
  _SeedDef('four_leaf_clover', '🍀', 'animals_&_nature'),
  _SeedDef('rose', '🌹', 'animals_&_nature'),
  _SeedDef('sunflower', '🌻', 'animals_&_nature'),
  _SeedDef('fire', '🔥', 'animals_&_nature'),
  _SeedDef('sparkles', '✨', 'animals_&_nature'),
  _SeedDef('star', '⭐', 'animals_&_nature'),
  _SeedDef('star2', '🌟', 'animals_&_nature'),
  _SeedDef('zap', '⚡', 'animals_&_nature'),
  _SeedDef('rainbow', '🌈', 'animals_&_nature'),
  _SeedDef('sunny', '☀️', 'animals_&_nature'),
  _SeedDef('moon', '🌙', 'animals_&_nature'),

  // Food & Drink
  _SeedDef('coffee', '☕', 'food_&_drink'),
  _SeedDef('tea', '🍵', 'food_&_drink'),
  _SeedDef('bubble_tea', '🧋', 'food_&_drink'),
  _SeedDef('beer', '🍺', 'food_&_drink'),
  _SeedDef('beers', '🍻', 'food_&_drink'),
  _SeedDef('champagne', '🍾', 'food_&_drink'),
  _SeedDef('wine_glass', '🍷', 'food_&_drink'),
  _SeedDef('cake', '🍰', 'food_&_drink'),
  _SeedDef('birthday', '🎂', 'food_&_drink'),
  _SeedDef('pizza', '🍕', 'food_&_drink'),
  _SeedDef('hamburger', '🍔', 'food_&_drink'),
  _SeedDef('fries', '🍟', 'food_&_drink'),
  _SeedDef('popcorn', '🍿', 'food_&_drink'),
  _SeedDef('apple', '🍎', 'food_&_drink'),
  _SeedDef('watermelon', '🍉', 'food_&_drink'),
  _SeedDef('lemon', '🍋', 'food_&_drink'),

  // Activities & Travel
  _SeedDef('tada', '🎉', 'activities'),
  _SeedDef('confetti_ball', '🎊', 'activities'),
  _SeedDef('gift', '🎁', 'activities'),
  _SeedDef('balloon', '🎈', 'activities'),
  _SeedDef('trophy', '🏆', 'activities'),
  _SeedDef('medal_sports', '🏅', 'activities'),
  _SeedDef('first_place_medal', '🥇', 'activities'),
  _SeedDef('crown', '👑', 'activities'),
  _SeedDef('gem', '💎', 'activities'),
  _SeedDef('dart', '🎯', 'activities'),
  _SeedDef('video_game', '🎮', 'activities'),
  _SeedDef('game_die', '🎲', 'activities'),
  _SeedDef('rocket', '🚀', 'travel_&_places'),
  _SeedDef('airplane', '✈️', 'travel_&_places'),
  _SeedDef('earth_asia', '🌏', 'travel_&_places'),

  // Objects & Symbols
  _SeedDef('bulb', '💡', 'objects'),
  _SeedDef('gear', '⚙️', 'objects'),
  _SeedDef('wrench', '🔧', 'objects'),
  _SeedDef('hammer_and_wrench', '🛠️', 'objects'),
  _SeedDef('shield', '🛡️', 'objects'),
  _SeedDef('link', '🔗', 'objects'),
  _SeedDef('lock', '🔒', 'objects'),
  _SeedDef('unlock', '🔓', 'objects'),
  _SeedDef('key', '🔑', 'objects'),
  _SeedDef('mag', '🔍', 'objects'),
  _SeedDef('mag_right', '🔎', 'objects'),
  _SeedDef('memo', '📝', 'objects'),
  _SeedDef('book', '📖', 'objects'),
  _SeedDef('books', '📚', 'objects'),
  _SeedDef('bookmark', '🔖', 'objects'),
  _SeedDef('pushpin', '📌', 'objects'),
  _SeedDef('round_pushpin', '📍', 'objects'),
  _SeedDef('paperclip', '📎', 'objects'),
  _SeedDef('package', '📦', 'objects'),
  _SeedDef('computer', '💻', 'objects'),
  _SeedDef('keyboard', '⌨️', 'objects'),
  _SeedDef('iphone', '📱', 'objects'),
  _SeedDef('hourglass_flowing_sand', '⏳', 'objects'),
  _SeedDef('alarm_clock', '⏰', 'objects'),
  _SeedDef('moneybag', '💰', 'objects'),
  _SeedDef('chart_with_upwards_trend', '📈', 'objects'),
  _SeedDef('bell', '🔔', 'objects'),
  _SeedDef('mega', '📣', 'objects'),
  _SeedDef('loudspeaker', '📢', 'objects'),
  _SeedDef('warning', '⚠️', 'symbols'),
  _SeedDef('no_entry', '⛔', 'symbols'),
  _SeedDef('prohibited', '🚫', 'symbols'),
  _SeedDef('white_check_mark', '✅', 'symbols'),
  _SeedDef('heavy_check_mark', '✔️', 'symbols'),
  _SeedDef('x', '❌', 'symbols'),
  _SeedDef('question', '❓', 'symbols'),
  _SeedDef('grey_question', '❔', 'symbols'),
  _SeedDef('exclamation', '❗', 'symbols'),
  _SeedDef('bangbang', '‼️', 'symbols'),
  _SeedDef('interrobang', '⁉️', 'symbols'),
  _SeedDef('recycle', '♻️', 'symbols'),
  _SeedDef('infinity', '♾️', 'symbols'),
];

const List<String> _kDefaultReactionNames = <String>[
  'heart',
  '+1',
  'laughing',
  'open_mouth',
  'clap',
  'confetti_ball',
  'hugs',
  'rocket',
];

const Map<int, String> _kFitzpatrickSkinToneModifiers = <int, String>{
  2: '\u{1F3FB}',
  3: '\u{1F3FC}',
  4: '\u{1F3FD}',
  5: '\u{1F3FE}',
  6: '\u{1F3FF}',
};

class LinuxDoEmojiCatalog {
  static final LinuxDoEmojiCatalog instance = LinuxDoEmojiCatalog._();

  static final RegExp _skinToneRegex = RegExp(r'^([a-z0-9_+-]+):t([2-6])$');

  final Map<String, EmojiCatalogItem> _itemsByName = <String, EmojiCatalogItem>{};
  final Map<String, List<EmojiCatalogItem>> _grouped =
      <String, List<EmojiCatalogItem>>{};

  LinuxDoEmojiCatalog._() {
    for (final def in _kPreseededEmojiDefs) {
      final item = EmojiCatalogItem(
        name: def.name,
        url: _twitterEmojiUrl(def.name),
        group: def.group,
        unicodeChar: def.unicode,
      );
      _registerItem(item, replaceExisting: false);
    }
  }

  Map<String, List<EmojiCatalogItem>> get groupedEmojis {
    final snapshot = <String, List<EmojiCatalogItem>>{};
    for (final entry in _grouped.entries) {
      if (entry.value.isNotEmpty) {
        snapshot[entry.key] = List<EmojiCatalogItem>.unmodifiable(entry.value);
      }
    }
    return Map<String, List<EmojiCatalogItem>>.unmodifiable(snapshot);
  }

  List<EmojiCatalogItem> get defaultReactions {
    final result = <EmojiCatalogItem>[];
    for (final name in _kDefaultReactionNames) {
      final item = _itemsByName[name];
      if (item != null) {
        result.add(item);
      } else {
        result.add(EmojiCatalogItem(name: name, url: _twitterEmojiUrl(name)));
      }
    }
    return List<EmojiCatalogItem>.unmodifiable(result);
  }

  String? resolveEmojiUrl(String shortcodeOrName) {
    final clean = _normalizeShortcode(shortcodeOrName);
    if (clean.isEmpty) return null;

    final direct = _itemsByName[clean];
    if (direct != null) return direct.url;

    final toneMatch = _skinToneRegex.firstMatch(clean);
    if (toneMatch != null) {
      final baseName = toneMatch.group(1)!;
      final tone = toneMatch.group(2)!;
      final baseItem = _itemsByName[baseName];
      if (baseItem != null) {
        final canonicalBase = baseName == 'thumbsup'
            ? '+1'
            : (baseName == 'thumbsdown' ? '-1' : baseName);
        return '$kLinuxDoBaseUrl/images/emoji/twitter/$canonicalBase/$tone.png?v=12';
      }
    }

    return null;
  }

  String? resolveUnicodeEmoji(String shortcodeOrName) {
    final clean = _normalizeShortcode(shortcodeOrName);
    if (clean.isEmpty) return null;

    final direct = _itemsByName[clean];
    if (direct?.unicodeChar != null && direct!.unicodeChar!.isNotEmpty) {
      return direct.unicodeChar;
    }

    final toneMatch = _skinToneRegex.firstMatch(clean);
    if (toneMatch != null) {
      final baseName = toneMatch.group(1)!;
      final toneNum = int.tryParse(toneMatch.group(2) ?? '');
      final baseUnicode = _itemsByName[baseName]?.unicodeChar;
      if (baseUnicode != null && baseUnicode.isNotEmpty) {
        final modifier = toneNum == null ? null : _kFitzpatrickSkinToneModifiers[toneNum];
        if (modifier != null) {
          final strippedVariation = baseUnicode.replaceAll('\uFE0F', '');
          return '$strippedVariation$modifier';
        }
        return baseUnicode;
      }
    }

    return null;
  }

  void mergeRemoteEmojisJson(Map<String, dynamic> json) {
    for (final entry in json.entries) {
      final groupName = entry.key.trim().isEmpty ? 'default' : entry.key.trim();
      final value = entry.value;
      if (value is! List) continue;

      for (final rawItem in value) {
        if (rawItem is! Map) continue;
        final rawName = rawItem['name']?.toString() ?? '';
        final rawUrl = rawItem['url']?.toString() ?? '';
        final cleanName = _normalizeShortcode(rawName);
        if (cleanName.isEmpty || rawUrl.trim().isEmpty) continue;

        final resolvedUrl = resolveDiscourseUrl(rawUrl);
        if (resolvedUrl.isEmpty) continue;

        final existing = _itemsByName[cleanName];
        final mergedItem = EmojiCatalogItem(
          name: cleanName,
          url: resolvedUrl,
          group: groupName,
          unicodeChar: existing?.unicodeChar,
        );
        _registerItem(mergedItem, replaceExisting: true);
      }
    }
  }

  List<EmojiCatalogItem> search(String query) {
    final cleanQuery = _normalizeShortcode(query);
    if (cleanQuery.isEmpty) {
      return List<EmojiCatalogItem>.unmodifiable(_itemsByName.values);
    }

    final exactMatches = <EmojiCatalogItem>[];
    final prefixMatches = <EmojiCatalogItem>[];
    final containsMatches = <EmojiCatalogItem>[];

    for (final item in _itemsByName.values) {
      if (item.name == cleanQuery || item.unicodeChar == query.trim()) {
        exactMatches.add(item);
      } else if (item.name.startsWith(cleanQuery)) {
        prefixMatches.add(item);
      } else if (item.name.contains(cleanQuery)) {
        containsMatches.add(item);
      }
    }

    return List<EmojiCatalogItem>.unmodifiable(<EmojiCatalogItem>[
      ...exactMatches,
      ...prefixMatches,
      ...containsMatches,
    ]);
  }

  static String _normalizeShortcode(String raw) {
    var trimmed = raw.trim();
    if (trimmed.startsWith(':')) {
      trimmed = trimmed.substring(1);
    }
    if (trimmed.endsWith(':')) {
      trimmed = trimmed.substring(0, trimmed.length - 1);
    }
    return trimmed.trim().toLowerCase();
  }

  void _registerItem(EmojiCatalogItem item, {required bool replaceExisting}) {
    final existing = _itemsByName[item.name];
    if (existing != null && !replaceExisting) {
      return;
    }
    if (existing != null && existing.group != item.group) {
      _grouped[existing.group]?.removeWhere((e) => e.name == item.name);
    }
    _itemsByName[item.name] = item;
    final list = _grouped.putIfAbsent(item.group, () => <EmojiCatalogItem>[]);
    final existingIdx = list.indexWhere((e) => e.name == item.name);
    if (existingIdx >= 0) {
      list[existingIdx] = item;
    } else {
      list.add(item);
    }
  }
}
