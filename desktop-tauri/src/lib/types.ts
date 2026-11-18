export type MainTab = "HOME" | "DISCOVER" | "MESSAGES" | "PROFILE";

export type FeedSort = "latest" | "top" | "new" | "unread" | "follow";
export type TopPeriod = "daily" | "weekly" | "monthly" | "quarterly" | "yearly" | "all";

export interface UserProfile {
  userId: number;
  username: string;
  displayName: string;
  avatarTemplate: string;
  animatedAvatar?: string | null;
  trustLevel: number;
  userTitle?: string | null;
}

export interface SavedAccountEntry {
  profile: UserProfile;
  cookies: string;
  needsLogin: boolean;
  readFloors: Record<string, number>;
}

export interface UserPreferences {
  appearance: "system" | "light" | "dark";
  themeOption: string;
  fontScale: number;
  splitFraction: number;
  compactFeed: boolean;
  defaultSearchExpanded: boolean;
  enableAnimations: boolean;
}

export interface LocalDraftRecord {
  key: string;
  action: "NEW_TOPIC" | "REPLY" | "EDIT";
  topicId?: number | null;
  postId?: number | null;
  replyToPostNumber?: number | null;
  categoryId?: number | null;
  title: string;
  raw: string;
  tags: string[];
  sequence: number;
  updatedAt: number;
}

export interface CloudDraftItem {
  draftKey: string;
  sequence: number;
  action: "NEW_TOPIC" | "REPLY" | "EDIT";
  topicId?: number | null;
  postId?: number | null;
  replyToPostNumber?: number | null;
  categoryId?: number | null;
  title: string;
  raw: string;
  tags: string[];
  createdAt?: string;
}

export interface PersistedState {
  currentUser?: UserProfile | null;
  rawCookies: string;
  clientId: string;
  expiredUsername?: string | null;
  unreadNotifications: number;
  savedAccounts: SavedAccountEntry[];
  preferences: UserPreferences;
  readFloors: Record<string, number>;
  drafts: Record<string, LocalDraftRecord>;
}

export interface BootstrapPayload {
  isAuthorized: boolean;
  state: PersistedState;
  platform: "macos" | "windows" | "linux";
}

export interface SiteHttpResponse {
  status: number;
  body: string;
  isChallenge: boolean;
  isUnauthenticated: boolean;
  rateLimitedMs?: number | null;
}

export interface CategoryMeta {
  id: number;
  name: string;
  displayName: string;
  slug: string;
  colorHex: string;
  description: string;
  readRestricted: boolean;
  parentId?: number | null;
  parentSlug?: string | null;
  hasServerParent?: boolean;
  subLabel?: string | null;
  trustLevel?: number | null;
  icon?: string | null;
  canCreateTopic?: boolean;
  topicTemplate?: string;
  topicCount?: number;
}

export interface FlairInfo {
  url?: string | null;
  name?: string | null;
  bgColorHex?: string | null;
  fgColorHex?: string | null;
  groupId?: number | null;
}

export interface UserStatusInfo {
  description?: string | null;
  emoji?: string | null;
}

export interface GrantedBadge {
  id: number;
  name: string;
  description: string;
  longDescription?: string;
  badgeTypeId: number; // 1=金徽章, 2=银徽章, 3=铜徽章
  icon?: string | null;
  imageUrl?: string | null;
  slug?: string;
  grantCount?: number;
  allowTitle?: boolean;
  badgeGroupingId?: number;
  isFirstTimeGrant?: boolean;
  count?: number;
  grantedAt?: string;
  isEarned?: boolean;
}

export interface TopicPoster {
  userId: number;
  username: string;
  name?: string;
  avatarTemplate: string;
  animatedAvatar?: string | null;
  primaryGroupName?: string | null;
  flair?: FlairInfo | null;
}

export interface TopicSummary {
  id: number;
  title: string;
  slug: string;
  excerpt?: string;
  postsCount: number;
  replyCount: number;
  views: number;
  likeCount: number;
  highestPostNumber: number;
  lastReadPostNumber?: number;
  unreadPosts: number;
  unseen: boolean;
  pinned: boolean;
  closed: boolean;
  archived: boolean;
  bookmarked: boolean;
  hasAcceptedAnswer?: boolean;
  categoryId?: number;
  categoryName?: string;
  categoryColorHex?: string;
  tags: string[];
  createdAt: string;
  lastPostedAt: string;
  originalPoster: TopicPoster;
}

export interface PostReactionItem {
  id: string;
  count: number;
}

export interface PostBoostItem {
  id: number;
  username: string;
  displayName?: string;
  avatarTemplate: string;
  cooked: string;
  canDelete?: boolean;
}

export interface PostPollOption {
  id: string;
  html: string;
  votes: number;
}

export interface PostPollModel {
  name: string;
  type: "regular" | "multiple" | "number";
  status: "open" | "closed";
  results: "always" | "on_vote" | "on_close" | "staff_only";
  options: PostPollOption[];
  voters: number;
  min?: number;
  max?: number;
  title?: string;
  myVotes?: string[];
}

export interface PostItemModel {
  id: number;
  topicId: number;
  postNumber: number;
  userId?: number;
  username: string;
  displayName: string;
  avatarTemplate: string;
  animatedAvatar?: string | null;
  primaryGroupName?: string | null;
  userTitle?: string | null;
  titleIsGroup?: boolean;
  trustLevel: number;
  isModerator: boolean;
  isGroupModerator?: boolean;
  isAdmin: boolean;
  flair?: FlairInfo | null;
  userStatus?: UserStatusInfo | null;
  iosDeviceName?: string | null;
  isTodayBirthday?: boolean;
  isTodayCakeday?: boolean;
  badgesGranted?: GrantedBadge[];
  authorBadges?: GrantedBadge[];
  createdAt: string;
  cookedHtml: string;
  raw?: string;
  replyCount: number;
  replyToPostNumber?: number | null;
  replyToUsername?: string | null;
  replyToDisplayName?: string | null;
  replyToAvatarTemplate?: string | null;
  likeCount: number;
  liked: boolean;
  isRead?: boolean;
  currentUserReaction?: string | null;
  reactions: PostReactionItem[];
  boosts: PostBoostItem[];
  canBoost?: boolean;
  polls: PostPollModel[];
  bookmarked: boolean;
  bookmarkId?: number | null;
  canEdit: boolean;
  canDelete: boolean;
  isSmallAction?: boolean;
  actionCode?: string | null;
  isDeletedTombstone?: boolean;
  acceptedAnswer: boolean;
  canAcceptAnswer: boolean;
}

export interface TopicDetailModel {
  id: number;
  title: string;
  archetype?: string;
  categoryId?: number;
  categoryName?: string;
  categoryColorHex?: string;
  tags: string[];
  views: number;
  postsCount: number;
  likeCount: number;
  bookmarked: boolean;
  closed: boolean;
  archived: boolean;
  notificationLevel: number;
  acceptedAnswerPostNumber?: number | null;
  lastReadPostNumber?: number | null;
  opUsername: string;
  streamIds: number[];
  posts: PostItemModel[];
  hasSummary: boolean;
  summarizable?: boolean;
}

export interface ConnectMetricItem {
  label: string;
  currentValue: number;
  targetValue: number;
  displayCurrent: string;
  displayTarget: string;
  isMet: boolean;
  progress: number;
}

export interface ConnectTrustLevelReport {
  title: string;
  subtitle: string;
  statusBadgeText: string;
  isQualified: boolean;
  timePeriodDays: number;
  metrics: ConnectMetricItem[];
  isLiveConnectData: boolean;
}

export interface NotificationItem {
  id: number;
  notificationType: number;
  read: boolean;
  createdAt: string;
  topicId?: number;
  postNumber?: number;
  originalPostId?: number;
  slug?: string;
  title: string;
  actorUsername: string;
  actorAvatarTemplate?: string;
  excerpt?: string;
  count?: number;
  reactionIcon?: string;
  isConsolidatedReplies?: boolean;
  badgeId?: number;
  badgeName?: string;
  chatChannelId?: number;
}

export interface TagChoiceOption {
  name: string;
  id?: number | null;
  count: number;
  disabled?: boolean;
  reason?: string | null;
}

export interface TagChoiceResult {
  options: TagChoiceOption[];
  requiredGroup?: string | null;
  forbiddenMessage?: string | null;
  canCreateTag?: boolean;
}

export interface MentionUserCandidate {
  username: string;
  name?: string;
  avatarTemplate: string;
  isGroup?: boolean;
}

export interface FollowUserEntry {
  username: string;
  displayName: string;
  avatarTemplate: string;
  animatedAvatar?: string | null;
  bio?: string;
  isFollowing: boolean;
}

export interface UserAccountPreferencesSnapshot {
  username: string;
  displayName: string;
  email: string;
  bioRaw: string;
  title: string;
  location: string;
  website: string;
  trustLevel: number;
  createdAtText: string;
  hideProfileAndPresence: boolean;
  allowPrivateMessages: boolean;
  availableTitles: string[];
}

export interface ComposerLaunchRequest {
  action: "NEW_TOPIC" | "REPLY" | "EDIT";
  topicId?: number;
  topicTitle?: string;
  postId?: number;
  postNumber?: number;
  replyToPostNumber?: number;
  replyToUsername?: string;
  replyToSnippet?: string;
  initialCategoryId?: number;
  initialTitle?: string;
  initialRaw?: string;
  initialTags?: string[];
  draftKeyOverride?: string;
}

export interface DesktopUpdateInfo {
  currentVersion: string;
  latestVersion: string;
  hasUpdate: boolean;
  arch: string;
  zipUrl: string;
  dmgUrl: string;
  sha256: string;
  updatedAt: string;
  notes: string[];
}

export interface UpdateProgressPayload {
  stage: "downloading" | "verifying" | "installing" | "restarting" | "done";
  downloadedBytes: number;
  totalBytes: number;
  percent: number;
  message: string;
}

export interface LocalHistoryItem {
  topicId: number;
  title: string;
  categoryId?: number;
  categoryName?: string;
  categoryColorHex?: string;
  authorUsername: string;
  authorName?: string;
  authorAvatar: string;
  replyCount: number;
  views: number;
  visitedAt: string;
}
