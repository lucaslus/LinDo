package do_.linux.lindo.core.copy

/**
 * 导航栏、首页话题流、下拉刷新、发现生态页与全局搜索文案枚举
 */
enum class LinDoFeedCopy(override val text: String) : LinDoCopyEntry {
    // 主题配色与主导航枚举文案
    THEME_OFFICIAL_GOLD("金"),
    THEME_IDEAL_RED("红"),
    THEME_GEEK_BLUE("蓝"),
    THEME_BAMBOO_GREEN("绿"),
    THEME_SOFT_PURPLE("紫"),
    THEME_CLEAR_TEAL("青"),
    THEME_WARM_ORANGE("橙"),

    TAB_HOME("首页"),
    TAB_DISCOVER("发现"),
    TAB_MESSAGES("消息"),
    TAB_PROFILE("我的"),
    PUBLISH_NEW_TOPIC_DESC("发布新话题"),
    DARK_MODE_DESC("深色模式"),
    SWITCH_TO_LIGHT_MODE_DESC("切换至浅色模式"),
    SWITCH_TO_DARK_MODE_DESC("切换至深色模式"),

    // 话题排序与热门周期
    SORT_LATEST("最新"),
    SORT_TOP("热门"),
    SORT_NEW("新帖"),
    SORT_UNREAD("未读"),
    SORT_FOLLOW("关注"),

    PERIOD_DAILY("今日"),
    PERIOD_WEEKLY("本周"),
    PERIOD_MONTHLY("本月"),
    PERIOD_YEARLY("今年"),
    PERIOD_ALL("全部"),

    // 首页信息流与筛选栏
    NOTIFICATIONS_DESC("消息通知"),
    GLOBAL_SEARCH_DESC("全站搜索"),
    SEARCH_PLACEHOLDER("搜索话题、标签或用户"),
    CATEGORY_FILTER_BUTTON("分类"),
    COLLAPSE_CATEGORY_FILTER_DESC("收起分类筛选"),
    EXPAND_CATEGORY_FILTER_DESC("展开分类筛选"),
    REFRESH_LIST_DESC("刷新列表"),
    TOGGLE_FEED_LAYOUT_DESC("切换列表展示形式"),
    RESTRICTED_CATEGORY_DESC("受限版块"),
    CLEAR_CATEGORY_FILTER_LABEL("清除分类筛选"),
    CLEAR_TAG_FILTER_LABEL("清除标签筛选"),
    NEW_ACTIVITY_BANNER("{0} 条新动态"),
    EMPTY_TOPICS("暂无话题"),
    PINNED_BADGE("置顶"),

    // 下拉刷新提示
    PULL_REFRESHING("刷新中"),
    PULL_RELEASE_TO_REFRESH("松开刷新"),
    PULL_DOWN_TO_REFRESH("下拉刷新"),
    PULL_UP_TO_DATE("已是最新"),

    // 发现页版块、标签与官方生态入口
    SECTION_COMMUNITY_CATEGORIES("社区版块"),
    SECTION_POPULAR_TAGS("热门标签"),
    SECTION_OFFICIAL_ECOSYSTEM("官方生态"),
    LOGIN_REQUIRED_BADGE("需登录访问"),
    EXPAND_SUBCATEGORIES_DESC("展开子版块等级"),
    SUBCATEGORY_NETDISK_LV_RANGE("网盘/Lv{0}-{1}"),
    SUBCATEGORY_LV_RANGE("Lv{0}-{1}"),
    SUBCATEGORY_LV_SINGLE("Lv{0}"),
    SUBCATEGORY_COUNT_BADGE("{0} 子项"),

    PORTAL_CONNECT_TITLE("Connect 中心"),
    PORTAL_CONNECT_SUB("保级 / 邮箱 / DeepLX"),
    PORTAL_LEADERBOARD_TITLE("积分与活跃榜"),
    PORTAL_LEADERBOARD_SUB("全站获赞与活跃排行"),
    PORTAL_BADGES_TITLE("全站徽章墙"),
    PORTAL_BADGES_SUB("荣誉徽章与头衔规则"),
    PORTAL_ABOUT_TITLE("关于与准则"),
    PORTAL_ABOUT_SUB("管理团队 / 统计 / 准则"),
    PORTAL_CREDIT_TITLE("Credit 积分"),
    PORTAL_CREDIT_SUB("积分流转与服务认证"),
    PORTAL_CDK_TITLE("CDK 分发"),
    PORTAL_CDK_SUB("社区福利领取与发布"),
    PORTAL_HUB_TITLE("LD Hub"),
    PORTAL_HUB_SUB("生态 AI 与聚合中心"),
    PORTAL_WIKI_TITLE("社区 Wiki"),
    PORTAL_WIKI_SUB("共建指南与文档库"),
    PORTAL_IDC_FLARE_TITLE("IDC Flare"),
    PORTAL_IDC_FLARE_SUB("官方云与主机生态站"),
    PORTAL_LOTTERY_TITLE("Lottery 抽奖"),
    PORTAL_LOTTERY_SUB("官方楼层开奖工具"),

    // 全局搜索弹窗
    GLOBAL_SEARCH_TITLE("全站搜索"),
    CLEAR_SEARCH_DESC("清空搜索"),
    SEARCH_FAILED_RETRY("搜索失败，请重试"),
    SEARCH_SORT_RELEVANCE("相关性"),
    SEARCH_SORT_LATEST("最新发帖"),
    SEARCH_SORT_LIKES("最多获赞"),
    HOT_SEARCH_KEYWORDS_TITLE("热门搜索关键词"),
    SEARCH_NO_RESULTS("未找到结果，试试其他关键词"),
    SEARCH_DISCUSSIONS_COUNT("{0} 条讨论"),
    SEARCH_END_TOTAL_COUNT("没有更多了 · 共找到 {0} 条相关结果");

    override fun toString(): String = text
}
