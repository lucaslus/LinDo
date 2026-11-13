import '../copy/lindo_copy.dart';

const String kLinuxDoBaseUrl = 'https://linux.do';
const String kLinuxDoConnectUrl = 'https://connect.linux.do';

const String _base62Alphabet =
    '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';

final RegExp _avatarSizedSegmentRegex =
    RegExp(r'(/user_avatar/[^/]+/[^/]+/)(\d+)(/[^/?#]+)');
final RegExp _avatarUsernameRegex =
    RegExp(r'/user_avatar/[^/]+/([^/]+)/', caseSensitive: false);
final RegExp _letterAvatarUsernameRegex =
    RegExp(r'/letter_avatar(?:_proxy)?/v\d+/letter/[^/]/([^/]+)/', caseSensitive: false);

int normalizeDiscourseAvatarSize(int requestedSize) {
  if (requestedSize <= 24) return 24;
  if (requestedSize <= 48) return 48;
  if (requestedSize <= 96) return 96;
  if (requestedSize <= 144) return 144;
  return 288;
}

String resolveDiscourseAvatarUrl(
  String template, {
  int size = 96,
  String? animatedAvatar,
}) {
  final cleanAnimated = animatedAvatar?.trim();
  if (cleanAnimated != null && cleanAnimated.isNotEmpty) {
    return resolveDiscourseUrl(cleanAnimated);
  }
  final cleanTemplate = template.trim();
  if (cleanTemplate.isEmpty) return '';

  final snappedSize = normalizeDiscourseAvatarSize(size);
  String sized;
  if (cleanTemplate.contains('{size}')) {
    sized = cleanTemplate.replaceAll('{size}', '$snappedSize');
  } else {
    sized = cleanTemplate.replaceFirstMapped(
      _avatarSizedSegmentRegex,
      (match) => '${match.group(1)}$snappedSize${match.group(3)}',
    );
  }
  return resolveDiscourseUrl(sized);
}

String resolveDiscourseUrl(String rawUrl) {
  final trimmed = rawUrl.trim();
  if (trimmed.isEmpty) return '';

  if (trimmed.startsWith('upload://')) {
    final uploadTarget = trimmed.substring('upload://'.length).replaceFirst(RegExp(r'^/+'), '');
    if (uploadTarget.isEmpty) return '';
    return '$kLinuxDoBaseUrl/uploads/short-url/$uploadTarget';
  }

  if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
    return trimmed;
  }

  if (trimmed.startsWith('//')) {
    return 'https:$trimmed';
  }

  if (trimmed.startsWith('/')) {
    return '$kLinuxDoBaseUrl$trimmed';
  }

  if (trimmed.startsWith('uploads/') ||
      trimmed.startsWith('images/') ||
      trimmed.startsWith('user_avatar/') ||
      trimmed.startsWith('letter_avatar_proxy/')) {
    return '$kLinuxDoBaseUrl/$trimmed';
  }

  return trimmed;
}

/// Decodes a Discourse base62 short-upload token (from `upload://<token>.<ext>`
/// or `/uploads/short-url/<token>.<ext>`) into its 40-char lowercase SHA1 hex,
/// returning `null` if [shortUrlOrToken] is not valid base62.
String? decodeDiscourseUploadBase62Sha1(String shortUrlOrToken) {
  var token = shortUrlOrToken.trim();
  if (token.startsWith('upload://')) {
    token = token.substring('upload://'.length);
  } else if (token.contains('/uploads/short-url/')) {
    token = token.split('/uploads/short-url/').last;
  }
  final dotIdx = token.indexOf('.');
  if (dotIdx > 0) {
    token = token.substring(0, dotIdx);
  }
  if (token.isEmpty) return null;

  var value = BigInt.zero;
  final base = BigInt.from(62);
  for (final codeUnit in token.codeUnits) {
    final char = String.fromCharCode(codeUnit);
    final digit = _base62Alphabet.indexOf(char);
    if (digit < 0) return null;
    value = value * base + BigInt.from(digit);
  }
  final hex = value.toRadixString(16).toLowerCase();
  return hex.length < 40 ? hex.padLeft(40, '0') : hex;
}

({int topicId, int? postNumber})? parseInternalTopicUrl(String url) {
  final trimmed = url.trim();
  if (trimmed.isEmpty) return null;

  Uri? uri;
  if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
    uri = Uri.tryParse(trimmed);
    if (uri == null) return null;
    final host = uri.host.toLowerCase();
    if (host != 'linux.do' && !host.endsWith('.linux.do')) {
      return null;
    }
  } else if (trimmed.startsWith('//')) {
    uri = Uri.tryParse('https:$trimmed');
    if (uri == null) return null;
    final host = uri.host.toLowerCase();
    if (host != 'linux.do' && !host.endsWith('.linux.do')) {
      return null;
    }
  } else {
    final normalizedPath = trimmed.startsWith('/') ? trimmed : '/$trimmed';
    uri = Uri.tryParse('$kLinuxDoBaseUrl$normalizedPath');
    if (uri == null) return null;
  }

  final segments = uri.pathSegments
      .where((s) => s.isNotEmpty)
      .map((s) => s.endsWith('.json') ? s.substring(0, s.length - 5) : s)
      .toList(growable: false);

  if (segments.length < 2 || segments.first != 't') {
    return null;
  }

  // Patterns supported:
  // /t/12345
  // /t/12345/6
  // /t/slug/12345
  // /t/slug/12345/6
  // /t/topic/12345/6
  final secondAsInt = int.tryParse(segments[1]);
  if (secondAsInt != null && secondAsInt > 0) {
    if (segments.length >= 3) {
      final thirdAsInt = int.tryParse(segments[2]);
      if (thirdAsInt != null && thirdAsInt > 0) {
        return (topicId: secondAsInt, postNumber: thirdAsInt);
      }
    }
    return (topicId: secondAsInt, postNumber: null);
  }

  if (segments.length >= 3) {
    final thirdAsInt = int.tryParse(segments[2]);
    if (thirdAsInt != null && thirdAsInt > 0) {
      int? postNumber;
      if (segments.length >= 4) {
        final fourthAsInt = int.tryParse(segments[3]);
        if (fourthAsInt != null && fourthAsInt > 0) {
          postNumber = fourthAsInt;
        }
      }
      return (topicId: thirdAsInt, postNumber: postNumber);
    }
  }

  return null;
}

String formatCompactCount(int count) {
  if (count <= 0) return '0';
  if (count < 1000) return '$count';
  if (count < 10000) {
    final tenths = (count ~/ 100) / 10.0;
    final formatted = tenths.toStringAsFixed(1).replaceAll(RegExp(r'\.0$'), '');
    return '${formatted}k';
  }
  final wanTenths = (count ~/ 1000) / 10.0;
  final formatted = wanTenths.toStringAsFixed(1).replaceAll(RegExp(r'\.0$'), '');
  return '${formatted}w';
}

String formatRelativeTime(String? isoString) {
  final raw = isoString?.trim() ?? '';
  if (raw.isEmpty) return '';

  final parsed = DateTime.tryParse(raw);
  if (parsed == null) return raw;

  final nowUtc = DateTime.now().toUtc();
  final targetUtc = parsed.toUtc();
  final diff = nowUtc.difference(targetUtc);

  if (diff.inSeconds < 60) {
    return LinDoCommonCopy.JUST_NOW.text;
  }
  if (diff.inMinutes < 60) {
    return '${diff.inMinutes}m';
  }
  if (diff.inHours < 24) {
    return '${diff.inHours}h';
  }
  if (diff.inDays < 30) {
    return '${diff.inDays}d';
  }

  final localTarget = parsed.toLocal();
  final localNow = DateTime.now();
  final month = localTarget.month.toString().padLeft(2, '0');
  final day = localTarget.day.toString().padLeft(2, '0');
  if (localTarget.year == localNow.year) {
    return '$month-$day';
  }
  return '${localTarget.year}-$month';
}

String? extractUsernameFromAvatarUrl(String? avatarUrlOrTemplate) {
  final raw = avatarUrlOrTemplate?.trim() ?? '';
  if (raw.isEmpty) return null;
  final match = _avatarUsernameRegex.firstMatch(raw);
  if (match != null) {
    final user = Uri.decodeComponent(match.group(1) ?? '').trim();
    if (user.isNotEmpty) return user;
  }
  final letterMatch = _letterAvatarUsernameRegex.firstMatch(raw);
  if (letterMatch != null) {
    final user = Uri.decodeComponent(letterMatch.group(1) ?? '').trim();
    if (user.isNotEmpty) return user;
  }
  return null;
}

bool isSquareAvatarGroup(String? primaryGroupName) {
  final normalized = primaryGroupName?.trim().toLowerCase() ?? '';
  return normalized == 'g-merchant' || normalized.contains('merchant');
}

bool isHolographicTitle({
  String? primaryGroupName,
  bool titleIsGroup = false,
}) {
  if (titleIsGroup) return true;
  final g = primaryGroupName?.trim().toLowerCase() ?? '';
  return g == 'seed' || g.startsWith('lv') || g.contains('vip') || g == 'g-merchant';
}

class EcosystemPortalItem {
  final String key;
  final LinDoFeedCopy titleCopy;
  final LinDoFeedCopy descCopy;
  final String url;
  final GuestGateScenario? guestScenario;
  final bool opensConnectSheet;
  final bool opensLeaderboardSheet;
  final bool opensBadgesSheet;
  final bool opensAboutSheet;

  const EcosystemPortalItem({
    required this.key,
    required this.titleCopy,
    required this.descCopy,
    required this.url,
    this.guestScenario,
    this.opensConnectSheet = false,
    this.opensLeaderboardSheet = false,
    this.opensBadgesSheet = false,
    this.opensAboutSheet = false,
  });

  String get title => titleCopy.text;
  String get description => descCopy.text;
}

const List<EcosystemPortalItem> kOfficialEcosystemPortals = <EcosystemPortalItem>[
  EcosystemPortalItem(
    key: 'connect',
    titleCopy: LinDoFeedCopy.PORTAL_CONNECT_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_CONNECT_SUB,
    url: kLinuxDoConnectUrl,
    guestScenario: GuestGateScenario.CONNECT,
    opensConnectSheet: true,
  ),
  EcosystemPortalItem(
    key: 'credit',
    titleCopy: LinDoFeedCopy.PORTAL_CREDIT_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_CREDIT_SUB,
    url: 'https://credit.linux.do',
    guestScenario: GuestGateScenario.CREDIT,
  ),
  EcosystemPortalItem(
    key: 'cdk',
    titleCopy: LinDoFeedCopy.PORTAL_CDK_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_CDK_SUB,
    url: 'https://cdk.linux.do',
    guestScenario: GuestGateScenario.CDK,
  ),
  EcosystemPortalItem(
    key: 'nav',
    titleCopy: LinDoFeedCopy.PORTAL_HUB_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_HUB_SUB,
    url: 'https://nav.linux.do',
    guestScenario: GuestGateScenario.LD_HUB,
  ),
  EcosystemPortalItem(
    key: 'lottery',
    titleCopy: LinDoFeedCopy.PORTAL_LOTTERY_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_LOTTERY_SUB,
    url: 'https://lottery.linux.do',
    guestScenario: GuestGateScenario.LOTTERY,
  ),
  EcosystemPortalItem(
    key: 'wiki',
    titleCopy: LinDoFeedCopy.PORTAL_WIKI_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_WIKI_SUB,
    url: 'https://wiki.linux.do',
  ),
  EcosystemPortalItem(
    key: 'leaderboard',
    titleCopy: LinDoFeedCopy.PORTAL_LEADERBOARD_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_LEADERBOARD_SUB,
    url: 'https://linux.do/u?period=weekly',
    guestScenario: GuestGateScenario.LEADERBOARD,
    opensLeaderboardSheet: true,
  ),
  EcosystemPortalItem(
    key: 'badges',
    titleCopy: LinDoFeedCopy.PORTAL_BADGES_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_BADGES_SUB,
    url: 'https://linux.do/badges',
    opensBadgesSheet: true,
  ),
  EcosystemPortalItem(
    key: 'about',
    titleCopy: LinDoFeedCopy.PORTAL_ABOUT_TITLE,
    descCopy: LinDoFeedCopy.PORTAL_ABOUT_SUB,
    url: 'https://linux.do/about',
    opensAboutSheet: true,
  ),
];
