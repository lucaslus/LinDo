// ignore_for_file: constant_identifier_names
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';

/// 全局统一高清光学对齐矢量图标表 (`LinDoIcons`)：
/// 采用 Flutter 原生 TrueType/OTF 光学 hinting 矢量字形（Cupertino + Material Rounded/Outlined），
/// 在 macOS Retina / Windows ClearType / Android 高分屏任意字号下均严格对齐像素网格，彻底消除手工折线锯齿与毛边。
class LinDoIcons {
  LinDoIcons._();

  // 主导航栏
  static const IconData Home = Icons.home_outlined;
  static const IconData HomeSelected = Icons.home_rounded;
  static const IconData Compass = CupertinoIcons.compass;
  static const IconData CompassSelected = CupertinoIcons.compass_fill;
  static const IconData Plus = Icons.add_rounded;
  static const IconData Minus = Icons.remove_rounded;
  static const IconData Inbox = Icons.inbox_outlined;
  static const IconData Bell = CupertinoIcons.bell;
  static const IconData BellSelected = CupertinoIcons.bell_fill;
  static const IconData User = CupertinoIcons.person;
  static const IconData UserSelected = CupertinoIcons.person_fill;

  // 布局、搜索与通用操作
  static const IconData Search = CupertinoIcons.search;
  static const IconData LayoutCards = Icons.view_agenda_outlined;
  static const IconData LayoutCompact = Icons.format_list_bulleted_rounded;
  static const IconData ArrowLeft = Icons.arrow_back_rounded;
  static const IconData ArrowRight = Icons.arrow_forward_rounded;
  static const IconData ChevronRight = Icons.chevron_right_rounded;
  static const IconData ChevronDown = Icons.keyboard_arrow_down_rounded;
  static const IconData ChevronUp = Icons.keyboard_arrow_up_rounded;
  static const IconData Close = Icons.close_rounded;
  static const IconData Check = Icons.check_rounded;
  static const IconData CheckCircle = Icons.check_circle_rounded;
  static const IconData MoreHoriz = Icons.more_horiz_rounded;
  static const IconData Refresh = Icons.refresh_rounded;
  static const IconData ExternalLink = Icons.open_in_new_rounded;
  static const IconData Globe = CupertinoIcons.globe;
  static const IconData Link = Icons.link_rounded;
  static const IconData Share = CupertinoIcons.share;
  static const IconData Copy = Icons.content_copy_rounded;
  static const IconData Trash = CupertinoIcons.trash;
  static const IconData Edit = CupertinoIcons.pencil;
  static const IconData Eye = CupertinoIcons.eye;
  static const IconData EyeOff = CupertinoIcons.eye_slash;
  static const IconData Filter = Icons.tune_rounded;

  // 帖子互动与阅读器
  static const IconData Heart = CupertinoIcons.heart;
  static const IconData HeartFilled = CupertinoIcons.heart_fill;
  static const IconData MessageCircle = CupertinoIcons.chat_bubble;
  static const IconData Bookmark = CupertinoIcons.bookmark;
  static const IconData BookmarkFilled = CupertinoIcons.bookmark_fill;
  static const IconData Smile = Icons.sentiment_satisfied_alt_rounded;
  static const IconData Reply = CupertinoIcons.reply;
  static const IconData Quote = Icons.format_quote_rounded;
  static const IconData Rocket = Icons.rocket_launch_outlined;
  static const IconData BoostRocket = Icons.rocket_launch_rounded;
  static const IconData ThumbUp = Icons.thumb_up_alt_outlined;
  static const IconData Flame = CupertinoIcons.flame_fill;
  static const IconData Pin = CupertinoIcons.pin_fill;

  // Markdown 创作工作台
  static const IconData Image = CupertinoIcons.photo;
  static const IconData Paperclip = CupertinoIcons.paperclip;
  static const IconData AtSign = CupertinoIcons.at;
  static const IconData Tag = CupertinoIcons.tag;
  static const IconData Bold = Icons.format_bold_rounded;
  static const IconData Italic = Icons.format_italic_rounded;
  static const IconData Strikethrough = Icons.format_strikethrough_rounded;
  static const IconData Code = Icons.code_rounded;
  static const IconData ListBullet = Icons.format_list_bulleted_rounded;
  static const IconData ListTodo = Icons.checklist_rounded;
  static const IconData Table = Icons.table_chart_outlined;
  static const IconData Poll = Icons.poll_outlined;
  static const IconData Undo = Icons.undo_rounded;
  static const IconData Redo = Icons.redo_rounded;
  static const IconData Heading = Icons.title_rounded;

  // 身份、徽章、安全、生态与设置
  static const IconData ShieldCheck = CupertinoIcons.shield_lefthalf_fill;
  static const IconData Lock = CupertinoIcons.lock;
  static const IconData FaLock = CupertinoIcons.lock_fill;
  static const IconData InfoCircle = CupertinoIcons.info_circle;
  static const IconData Warning = Icons.warning_amber_rounded;
  static const IconData Sun = CupertinoIcons.sun_max;
  static const IconData Moon = CupertinoIcons.moon;
  static const IconData LogOut = Icons.logout_rounded;
  static const IconData Palette = Icons.palette_outlined;
  static const IconData Sliders = CupertinoIcons.slider_horizontal_3;
  static const IconData AppsGrid = CupertinoIcons.square_grid_2x2;
  static const IconData Sparkles = CupertinoIcons.sparkles;
  static const IconData Layers = CupertinoIcons.layers_alt;
  static const IconData BookOpen = CupertinoIcons.book;
  static const IconData Crown = Icons.workspace_premium_rounded;
  static const IconData Trophy = CupertinoIcons.rosette;
  static const IconData VerifiedBadge = Icons.verified_rounded;
  static const IconData Seedling = Icons.eco_rounded;
  static const IconData Bolt = Icons.bolt_rounded;
  static const IconData Star = Icons.star_rounded;
  static const IconData Bug = Icons.bug_report_outlined;
  static const IconData Coins = Icons.monetization_on_outlined;
  static const IconData ShoppingBag = CupertinoIcons.bag;
  static const IconData Megaphone = CupertinoIcons.speaker_2;
  static const IconData CpuChip = Icons.memory_rounded;
  static const IconData History = Icons.history_rounded;
  static const IconData Drafts = CupertinoIcons.doc_text;
  static const IconData Send = Icons.send_rounded;
}
