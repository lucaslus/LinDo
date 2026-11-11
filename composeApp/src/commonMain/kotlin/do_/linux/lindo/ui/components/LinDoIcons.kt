package do_.linux.lindo.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * LinDo Pro 矢量图标系统 (`LinDoIcons`)
 *
 * 设计语言对标 Lucide / Feather / SF Symbols / Linear：
 * - 统一 24×24 视口与 1.8dp 黄金描边粗细（选中态 2.2dp）
 * - 全程采用 `StrokeCap.Round` 与 `StrokeJoin.Round` 圆润笔触端点
 * - 彻底取代陈旧厚重的 Material 1 图标与散落在按钮中的 Emoji 字符
 */
object LinDoIcons {

    private inline fun lucideIcon(
        name: String,
        strokeWidth: Float = 1.8f,
        block: ImageVector.Builder.() -> Unit
    ): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()
    }

    private fun ImageVector.Builder.strokePath(
        strokeWidth: Float = 1.8f,
        pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit
    ) {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
            pathBuilder = pathBuilder
        )
    }

    // ========================================================================
    // 1. 底部导航栏核心图标 (首页 · 发现 · 发布 · 消息 · 我的)
    // ========================================================================

    val Home: ImageVector by lazy {
        lucideIcon("Home") {
            strokePath(1.8f) {
                moveTo(3f, 10.2f)
                lineTo(12f, 3f)
                lineTo(21f, 10.2f)
                verticalLineTo(20f)
                curveTo(21f, 20.55f, 20.55f, 21f, 20f, 21f)
                horizontalLineTo(4f)
                curveTo(3.45f, 21f, 3f, 20.55f, 3f, 20f)
                close()
            }
            strokePath(1.8f) {
                moveTo(9.5f, 21f)
                verticalLineTo(14.5f)
                curveTo(9.5f, 13.95f, 9.95f, 13.5f, 10.5f, 13.5f)
                horizontalLineTo(13.5f)
                curveTo(14.05f, 13.5f, 14.5f, 13.95f, 14.5f, 14.5f)
                verticalLineTo(21f)
            }
        }
    }

    val HomeSelected: ImageVector by lazy {
        lucideIcon("HomeSelected") {
            path(
                fill = SolidColor(Color.Black.copy(alpha = 0.14f)),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 10.2f)
                lineTo(12f, 3f)
                lineTo(21f, 10.2f)
                verticalLineTo(20f)
                curveTo(21f, 20.55f, 20.55f, 21f, 20f, 21f)
                horizontalLineTo(4f)
                curveTo(3.45f, 21f, 3f, 20.55f, 3f, 20f)
                close()
            }
            strokePath(2.2f) {
                moveTo(9.5f, 21f)
                verticalLineTo(14.5f)
                curveTo(9.5f, 13.95f, 9.95f, 13.5f, 10.5f, 13.5f)
                horizontalLineTo(13.5f)
                curveTo(14.05f, 13.5f, 14.5f, 13.95f, 14.5f, 14.5f)
                verticalLineTo(21f)
            }
        }
    }

    val Compass: ImageVector by lazy {
        lucideIcon("Compass") {
            strokePath(1.85f) {
                moveTo(12f, 21f)
                curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
                curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
                curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
                curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
                close()
            }
            strokePath(1.85f) {
                moveTo(16.24f, 7.76f)
                lineTo(14.12f, 14.12f)
                lineTo(7.76f, 16.24f)
                lineTo(9.88f, 9.88f)
                close()
            }
        }
    }

    val CompassSelected: ImageVector by lazy {
        lucideIcon("CompassSelected") {
            path(
                fill = SolidColor(Color.Black.copy(alpha = 0.14f)),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 21f)
                curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
                curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
                curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
                curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
                close()
            }
            strokePath(2.2f) {
                moveTo(16.24f, 7.76f)
                lineTo(14.12f, 14.12f)
                lineTo(7.76f, 16.24f)
                lineTo(9.88f, 9.88f)
                close()
            }
        }
    }

    val Plus: ImageVector by lazy {
        lucideIcon("Plus") {
            strokePath(2.2f) {
                moveTo(12f, 5.5f)
                verticalLineTo(18.5f)
                moveTo(5.5f, 12f)
                horizontalLineTo(18.5f)
            }
        }
    }

    val Minus: ImageVector by lazy {
        lucideIcon("Minus") {
            strokePath(2.2f) {
                moveTo(5.5f, 12f)
                horizontalLineTo(18.5f)
            }
        }
    }

    val Inbox: ImageVector by lazy {
        lucideIcon("Inbox") {
            strokePath(1.8f) {
                moveTo(22f, 12f)
                horizontalLineTo(16f)
                lineTo(14f, 15f)
                horizontalLineTo(10f)
                lineTo(8f, 12f)
                horizontalLineTo(2f)
                moveTo(5.45f, 5.11f)
                lineTo(2f, 12f)
                verticalLineTo(18f)
                curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
                horizontalLineTo(20f)
                curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
                verticalLineTo(12f)
                lineTo(18.55f, 5.11f)
                curveTo(18.22f, 4.43f, 17.53f, 4f, 16.76f, 4f)
                horizontalLineTo(7.24f)
                curveTo(6.47f, 4f, 5.78f, 4.43f, 5.45f, 5.11f)
                close()
            }
        }
    }

    val Bell: ImageVector by lazy {
        lucideIcon("Bell") {
            strokePath(1.85f) {
                moveTo(18f, 8f)
                curveTo(18f, 4.69f, 15.31f, 2f, 12f, 2f)
                curveTo(8.69f, 2f, 6f, 4.69f, 6f, 8f)
                curveTo(6f, 15f, 3f, 17f, 3f, 17f)
                horizontalLineTo(21f)
                curveTo(21f, 17f, 18f, 15f, 18f, 8f)
                close()
            }
            strokePath(1.85f) {
                moveTo(13.73f, 21f)
                curveTo(13.37f, 21.6f, 12.71f, 22f, 12f, 22f)
                curveTo(11.29f, 22f, 10.63f, 21.6f, 10.27f, 21f)
            }
        }
    }

    val BellSelected: ImageVector by lazy {
        lucideIcon("BellSelected") {
            path(
                fill = SolidColor(Color.Black.copy(alpha = 0.14f)),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(18f, 8f)
                curveTo(18f, 4.69f, 15.31f, 2f, 12f, 2f)
                curveTo(8.69f, 2f, 6f, 4.69f, 6f, 8f)
                curveTo(6f, 15f, 3f, 17f, 3f, 17f)
                horizontalLineTo(21f)
                curveTo(21f, 17f, 18f, 15f, 18f, 8f)
                close()
            }
            strokePath(2.2f) {
                moveTo(13.73f, 21f)
                curveTo(13.37f, 21.6f, 12.71f, 22f, 12f, 22f)
                curveTo(11.29f, 22f, 10.63f, 21.6f, 10.27f, 21f)
            }
        }
    }

    val User: ImageVector by lazy {
        lucideIcon("User") {
            strokePath(1.85f) {
                moveTo(20f, 21f)
                verticalLineTo(19f)
                curveTo(20f, 16.79f, 18.21f, 15f, 16f, 15f)
                horizontalLineTo(8f)
                curveTo(5.79f, 15f, 4f, 16.79f, 4f, 19f)
                verticalLineTo(21f)
            }
            strokePath(1.85f) {
                moveTo(12f, 11f)
                curveTo(14.21f, 11f, 16f, 9.21f, 16f, 7f)
                curveTo(16f, 4.79f, 14.21f, 3f, 12f, 3f)
                curveTo(9.79f, 3f, 8f, 4.79f, 8f, 7f)
                curveTo(8f, 9.21f, 9.79f, 11f, 12f, 11f)
                close()
            }
        }
    }

    val UserSelected: ImageVector by lazy {
        lucideIcon("UserSelected") {
            strokePath(2.2f) {
                moveTo(20f, 21f)
                verticalLineTo(19f)
                curveTo(20f, 16.79f, 18.21f, 15f, 16f, 15f)
                horizontalLineTo(8f)
                curveTo(5.79f, 15f, 4f, 16.79f, 4f, 19f)
                verticalLineTo(21f)
            }
            path(
                fill = SolidColor(Color.Black.copy(alpha = 0.14f)),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 11f)
                curveTo(14.21f, 11f, 16f, 9.21f, 16f, 7f)
                curveTo(16f, 4.79f, 14.21f, 3f, 12f, 3f)
                curveTo(9.79f, 3f, 8f, 4.79f, 8f, 7f)
                curveTo(8f, 9.21f, 9.79f, 11f, 12f, 11f)
                close()
            }
        }
    }

    // ========================================================================
    // 2. 首页与话题详情交互图标 (搜索 · 密度切换 · 点赞 · 评论 · 收藏 · 表情 · 回复 · 引用)
    // ========================================================================

    val Search: ImageVector by lazy {
        lucideIcon("Search") {
            strokePath(1.8f) {
                moveTo(11f, 19f)
                curveTo(15.42f, 19f, 19f, 15.42f, 19f, 11f)
                curveTo(19f, 6.58f, 15.42f, 3f, 11f, 3f)
                curveTo(6.58f, 3f, 3f, 6.58f, 3f, 11f)
                curveTo(3f, 15.42f, 6.58f, 19f, 11f, 19f)
                close()
                moveTo(21f, 21f)
                lineTo(16.65f, 16.65f)
            }
        }
    }

    val LayoutCards: ImageVector by lazy {
        lucideIcon("LayoutCards") {
            strokePath(1.9f) {
                // 宽松列表视图：两组「左侧圆点 + 主标题长线 + 摘要短线」（与轻分割线宽松列表一一对应，严格处于 y=6.2..17.8 视觉框内）
                moveTo(4f, 6.5f)
                horizontalLineTo(5.2f)
                moveTo(8.5f, 6.5f)
                horizontalLineTo(20f)
                moveTo(8.5f, 9.8f)
                horizontalLineTo(15.5f)

                moveTo(4f, 14.5f)
                horizontalLineTo(5.2f)
                moveTo(8.5f, 14.5f)
                horizontalLineTo(20f)
                moveTo(8.5f, 17.8f)
                horizontalLineTo(15.5f)
            }
        }
    }

    val LayoutCompact: ImageVector by lazy {
        lucideIcon("LayoutCompact") {
            strokePath(1.9f) {
                // 紧凑列表视图：三行「左侧圆点 + 单行标题线」（与宽松图标同宽 x=4..20、同高 y=6.5..17.5）
                moveTo(4f, 6.5f)
                horizontalLineTo(5.2f)
                moveTo(8.5f, 6.5f)
                horizontalLineTo(20f)

                moveTo(4f, 12f)
                horizontalLineTo(5.2f)
                moveTo(8.5f, 12f)
                horizontalLineTo(20f)

                moveTo(4f, 17.5f)
                horizontalLineTo(5.2f)
                moveTo(8.5f, 17.5f)
                horizontalLineTo(20f)
            }
        }
    }

    val Heart: ImageVector by lazy {
        lucideIcon("Heart") {
            strokePath(1.8f) {
                moveTo(19f, 14f)
                curveTo(20.49f, 12.54f, 22f, 10.79f, 22f, 8.5f)
                curveTo(22f, 5.46f, 19.54f, 3f, 16.5f, 3f)
                curveTo(14.74f, 3f, 13.5f, 3.5f, 12f, 5.1f)
                curveTo(10.5f, 3.5f, 9.26f, 3f, 7.5f, 3f)
                curveTo(4.46f, 3f, 2f, 5.46f, 2f, 8.5f)
                curveTo(2f, 10.8f, 3.5f, 12.55f, 5f, 14f)
                lineTo(12f, 21f)
                close()
            }
        }
    }

    val HeartFilled: ImageVector by lazy {
        lucideIcon("HeartFilled") {
            path(
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.6f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(19f, 14f)
                curveTo(20.49f, 12.54f, 22f, 10.79f, 22f, 8.5f)
                curveTo(22f, 5.46f, 19.54f, 3f, 16.5f, 3f)
                curveTo(14.74f, 3f, 13.5f, 3.5f, 12f, 5.1f)
                curveTo(10.5f, 3.5f, 9.26f, 3f, 7.5f, 3f)
                curveTo(4.46f, 3f, 2f, 5.46f, 2f, 8.5f)
                curveTo(2f, 10.8f, 3.5f, 12.55f, 5f, 14f)
                lineTo(12f, 21f)
                close()
            }
        }
    }

    val MessageCircle: ImageVector by lazy {
        lucideIcon("MessageCircle") {
            strokePath(1.8f) {
                moveTo(7.9f, 20f)
                curveTo(9.18f, 20.65f, 10.6f, 21f, 12.1f, 21f)
                curveTo(17.07f, 21f, 21.1f, 16.97f, 21.1f, 12f)
                curveTo(21.1f, 7.03f, 17.07f, 3f, 12.1f, 3f)
                curveTo(7.13f, 3f, 3.1f, 7.03f, 3.1f, 12f)
                curveTo(3.1f, 13.5f, 3.45f, 14.92f, 4.1f, 16.2f)
                lineTo(2.6f, 21f)
                close()
            }
        }
    }

    val Bookmark: ImageVector by lazy {
        lucideIcon("Bookmark") {
            strokePath(1.8f) {
                moveTo(19f, 21f)
                lineTo(12f, 16f)
                lineTo(5f, 21f)
                verticalLineTo(5f)
                curveTo(5f, 3.9f, 5.9f, 3f, 7f, 3f)
                horizontalLineTo(17f)
                curveTo(18.1f, 3f, 19f, 3.9f, 19f, 5f)
                close()
            }
        }
    }

    val BookmarkFilled: ImageVector by lazy {
        lucideIcon("BookmarkFilled") {
            path(
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(19f, 21f)
                lineTo(12f, 16f)
                lineTo(5f, 21f)
                verticalLineTo(5f)
                curveTo(5f, 3.9f, 5.9f, 3f, 7f, 3f)
                horizontalLineTo(17f)
                curveTo(18.1f, 3f, 19f, 3.9f, 19f, 5f)
                close()
            }
        }
    }

    val Smile: ImageVector by lazy {
        lucideIcon("Smile") {
            strokePath(1.8f) {
                moveTo(12f, 21f)
                curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
                curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
                curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
                curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
                close()
                moveTo(8.5f, 14.2f)
                curveTo(9.4f, 15.5f, 10.6f, 16.1f, 12f, 16.1f)
                curveTo(13.4f, 16.1f, 14.6f, 15.5f, 15.5f, 14.2f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(9.2f, 8.6f)
                curveTo(9.8f, 8.6f, 10.3f, 9.1f, 10.3f, 9.7f)
                curveTo(10.3f, 10.3f, 9.8f, 10.8f, 9.2f, 10.8f)
                curveTo(8.6f, 10.8f, 8.1f, 10.3f, 8.1f, 9.7f)
                curveTo(8.1f, 9.1f, 8.6f, 8.6f, 9.2f, 8.6f)
                close()
                moveTo(14.8f, 8.6f)
                curveTo(15.4f, 8.6f, 15.9f, 9.1f, 15.9f, 9.7f)
                curveTo(15.9f, 10.3f, 15.4f, 10.8f, 14.8f, 10.8f)
                curveTo(14.2f, 10.8f, 13.7f, 10.3f, 13.7f, 9.7f)
                curveTo(13.7f, 9.1f, 14.2f, 8.6f, 14.8f, 8.6f)
                close()
            }
        }
    }

    val Reply: ImageVector by lazy {
        lucideIcon("Reply") {
            strokePath(1.85f) {
                moveTo(9f, 14f)
                lineTo(4f, 9f)
                lineTo(9f, 4f)
                moveTo(20f, 19f)
                verticalLineTo(14f)
                curveTo(20f, 11.24f, 17.76f, 9f, 15f, 9f)
                horizontalLineTo(4f)
            }
        }
    }

    val Quote: ImageVector by lazy {
        lucideIcon("Quote") {
            strokePath(1.8f) {
                moveTo(10f, 11f)
                horizontalLineTo(6.5f)
                curveTo(5.67f, 11f, 5f, 10.33f, 5f, 9.5f)
                verticalLineTo(7.5f)
                curveTo(5f, 6.67f, 5.67f, 6f, 6.5f, 6f)
                horizontalLineTo(8.5f)
                curveTo(9.33f, 6f, 10f, 6.67f, 10f, 7.5f)
                verticalLineTo(12.5f)
                curveTo(10f, 15f, 8.2f, 17f, 5.8f, 17.5f)
                moveTo(19f, 11f)
                horizontalLineTo(15.5f)
                curveTo(14.67f, 11f, 14f, 10.33f, 14f, 9.5f)
                verticalLineTo(7.5f)
                curveTo(14f, 6.67f, 14.67f, 6f, 15.5f, 6f)
                horizontalLineTo(17.5f)
                curveTo(18.33f, 6f, 19f, 6.67f, 19f, 7.5f)
                verticalLineTo(12.5f)
                curveTo(19f, 15f, 17.2f, 17f, 14.8f, 17.5f)
            }
        }
    }

    val ArrowLeft: ImageVector by lazy {
        lucideIcon("ArrowLeft") {
            strokePath(1.9f) {
                moveTo(19f, 12f)
                horizontalLineTo(5f)
                moveTo(12f, 19f)
                lineTo(5f, 12f)
                lineTo(12f, 5f)
            }
        }
    }

    val Close: ImageVector by lazy {
        lucideIcon("Close") {
            strokePath(1.9f) {
                moveTo(18f, 6f)
                lineTo(6f, 18f)
                moveTo(6f, 6f)
                lineTo(18f, 18f)
            }
        }
    }

    val MoreHoriz: ImageVector by lazy {
        lucideIcon("MoreHoriz") {
            strokePath(2.4f) {
                moveTo(5f, 12f)
                horizontalLineTo(5.02f)
                moveTo(12f, 12f)
                horizontalLineTo(12.02f)
                moveTo(19f, 12f)
                horizontalLineTo(19.02f)
            }
        }
    }

    val Edit: ImageVector by lazy {
        lucideIcon("Edit") {
            strokePath(1.8f) {
                moveTo(12f, 20f)
                horizontalLineTo(21f)
                moveTo(16.5f, 3.5f)
                curveTo(17.33f, 2.67f, 18.67f, 2.67f, 19.5f, 3.5f)
                curveTo(20.33f, 4.33f, 20.33f, 5.67f, 19.5f, 6.5f)
                lineTo(7f, 19f)
                lineTo(3f, 20f)
                lineTo(4f, 16f)
                close()
            }
        }
    }

    val Trash: ImageVector by lazy {
        lucideIcon("Trash") {
            strokePath(1.8f) {
                moveTo(3f, 6f)
                horizontalLineTo(21f)
                moveTo(8f, 6f)
                verticalLineTo(4f)
                curveTo(8f, 3.45f, 8.45f, 3f, 9f, 3f)
                horizontalLineTo(15f)
                curveTo(15.55f, 3f, 16f, 3.45f, 16f, 4f)
                verticalLineTo(6f)
                moveTo(19f, 6f)
                verticalLineTo(20f)
                curveTo(19f, 20.55f, 18.55f, 21f, 18f, 21f)
                horizontalLineTo(6f)
                curveTo(5.45f, 21f, 5f, 20.55f, 5f, 20f)
                verticalLineTo(6f)
            }
        }
    }

    val Link: ImageVector by lazy {
        lucideIcon("Link") {
            strokePath(1.8f) {
                moveTo(10f, 13f)
                curveTo(10.8f, 14.1f, 12.1f, 14.7f, 13.5f, 14.7f)
                curveTo(14.7f, 14.7f, 15.8f, 14.2f, 16.6f, 13.4f)
                lineTo(19.6f, 10.4f)
                curveTo(21.3f, 8.7f, 21.3f, 5.9f, 19.6f, 4.2f)
                curveTo(17.9f, 2.5f, 15.1f, 2.5f, 13.4f, 4.2f)
                lineTo(11.7f, 5.9f)
                moveTo(14f, 11f)
                curveTo(13.2f, 9.9f, 11.9f, 9.3f, 10.5f, 9.3f)
                curveTo(9.3f, 9.3f, 8.2f, 9.8f, 7.4f, 10.6f)
                lineTo(4.4f, 13.6f)
                curveTo(2.7f, 15.3f, 2.7f, 18.1f, 4.4f, 19.8f)
                curveTo(6.1f, 21.5f, 8.9f, 21.5f, 10.6f, 19.8f)
                lineTo(12.3f, 18.1f)
            }
        }
    }

    val Globe: ImageVector by lazy {
        lucideIcon("Globe") {
            strokePath(1.8f) {
                moveTo(12f, 21f)
                curveTo(16.97f, 21f, 21f, 16.97f, 21f, 12f)
                curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
                curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
                curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
                close()
                moveTo(3f, 12f)
                horizontalLineTo(21f)
                moveTo(12f, 3f)
                curveTo(14.5f, 5.5f, 15.8f, 8.7f, 15.8f, 12f)
                curveTo(15.8f, 15.3f, 14.5f, 18.5f, 12f, 21f)
                curveTo(9.5f, 18.5f, 8.2f, 15.3f, 8.2f, 12f)
                curveTo(8.2f, 8.7f, 9.5f, 5.5f, 12f, 3f)
            }
        }
    }

    val Refresh: ImageVector by lazy {
        lucideIcon("Refresh") {
            strokePath(1.8f) {
                moveTo(21f, 4f)
                verticalLineTo(10f)
                horizontalLineTo(15f)
                moveTo(20.49f, 15f)
                curveTo(19.24f, 18.39f, 15.91f, 20.8f, 12f, 20.8f)
                curveTo(7.03f, 20.8f, 3f, 16.77f, 3f, 11.8f)
                curveTo(3f, 6.83f, 7.03f, 2.8f, 12f, 2.8f)
                curveTo(15.72f, 2.8f, 18.92f, 5.05f, 20.3f, 8.26f)
                lineTo(21f, 10f)
            }
        }
    }

    // ========================================================================
    // 3. Markdown 工作台专业排版图标 (图片 · 附件 · @提及 · 标题 · 粗斜体 · 代码 · 列表 · 表格 · 投票 · 折叠 · 撤销)
    // ========================================================================

    val Image: ImageVector by lazy {
        lucideIcon("Image") {
            strokePath(1.8f) {
                moveTo(5f, 3f)
                horizontalLineTo(19f)
                curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
                verticalLineTo(19f)
                curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                verticalLineTo(5f)
                curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
                close()
                moveTo(8.5f, 10f)
                curveTo(9.33f, 10f, 10f, 9.33f, 10f, 8.5f)
                curveTo(10f, 7.67f, 9.33f, 7f, 8.5f, 7f)
                curveTo(7.67f, 7f, 7f, 7.67f, 7f, 8.5f)
                curveTo(7f, 9.33f, 7.67f, 10f, 8.5f, 10f)
                close()
                moveTo(21f, 15f)
                lineTo(16f, 10f)
                lineTo(5f, 21f)
            }
        }
    }

    val Paperclip: ImageVector by lazy {
        lucideIcon("Paperclip") {
            strokePath(1.8f) {
                moveTo(21.44f, 11.05f)
                lineTo(12.25f, 20.24f)
                curveTo(10.0f, 22.49f, 6.36f, 22.49f, 4.11f, 20.24f)
                curveTo(1.86f, 17.99f, 1.86f, 14.35f, 4.11f, 12.1f)
                lineTo(13.3f, 2.91f)
                curveTo(14.8f, 1.41f, 17.23f, 1.41f, 18.73f, 2.91f)
                curveTo(20.23f, 4.41f, 20.23f, 6.84f, 18.73f, 8.34f)
                lineTo(9.53f, 17.53f)
                curveTo(8.78f, 18.28f, 7.57f, 18.28f, 6.82f, 17.53f)
                curveTo(6.07f, 16.78f, 6.07f, 15.57f, 6.82f, 14.82f)
                lineTo(15.31f, 6.34f)
            }
        }
    }

    val AtSign: ImageVector by lazy {
        lucideIcon("AtSign") {
            strokePath(1.8f) {
                moveTo(16f, 8f)
                verticalLineTo(13f)
                curveTo(16f, 14.66f, 17.34f, 16f, 19f, 16f)
                curveTo(20.66f, 16f, 21f, 14.2f, 21f, 12f)
                curveTo(21f, 7.03f, 16.97f, 3f, 12f, 3f)
                curveTo(7.03f, 3f, 3f, 7.03f, 3f, 12f)
                curveTo(3f, 16.97f, 7.03f, 21f, 12f, 21f)
                curveTo(14.25f, 21f, 16.3f, 20.17f, 17.88f, 18.8f)
                moveTo(16f, 12f)
                curveTo(16f, 14.21f, 14.21f, 16f, 12f, 16f)
                curveTo(9.79f, 16f, 8f, 14.21f, 8f, 12f)
                curveTo(8f, 9.79f, 9.79f, 8f, 12f, 8f)
                curveTo(14.21f, 8f, 16f, 9.79f, 16f, 12f)
                close()
            }
        }
    }

    val Heading: ImageVector by lazy {
        lucideIcon("Heading") {
            strokePath(1.9f) {
                moveTo(6f, 4f)
                verticalLineTo(20f)
                moveTo(18f, 4f)
                verticalLineTo(20f)
                moveTo(6f, 12f)
                horizontalLineTo(18f)
            }
        }
    }

    val Bold: ImageVector by lazy {
        lucideIcon("Bold") {
            strokePath(1.9f) {
                moveTo(6f, 4f)
                horizontalLineTo(14f)
                curveTo(16.21f, 4f, 18f, 5.79f, 18f, 8f)
                curveTo(18f, 10.21f, 16.21f, 12f, 14f, 12f)
                horizontalLineTo(6f)
                close()
                moveTo(6f, 12f)
                horizontalLineTo(15f)
                curveTo(17.21f, 12f, 19f, 13.79f, 19f, 16f)
                curveTo(19f, 18.21f, 17.21f, 20f, 15f, 20f)
                horizontalLineTo(6f)
                close()
            }
        }
    }

    val Italic: ImageVector by lazy {
        lucideIcon("Italic") {
            strokePath(1.9f) {
                moveTo(19f, 4f)
                horizontalLineTo(10f)
                moveTo(14f, 20f)
                horizontalLineTo(5f)
                moveTo(15f, 4f)
                lineTo(9f, 20f)
            }
        }
    }

    val Code: ImageVector by lazy {
        lucideIcon("Code") {
            strokePath(1.8f) {
                moveTo(16f, 18f)
                lineTo(22f, 12f)
                lineTo(16f, 6f)
                moveTo(8f, 6f)
                lineTo(2f, 12f)
                lineTo(8f, 18f)
            }
        }
    }

    val Terminal: ImageVector by lazy {
        lucideIcon("Terminal") {
            strokePath(1.8f) {
                moveTo(4f, 17f)
                lineTo(10f, 11f)
                lineTo(4f, 5f)
                moveTo(12f, 19f)
                horizontalLineTo(20f)
            }
        }
    }

    val ListBullet: ImageVector by lazy {
        lucideIcon("ListBullet") {
            strokePath(1.8f) {
                moveTo(8f, 6f)
                horizontalLineTo(21f)
                moveTo(8f, 12f)
                horizontalLineTo(21f)
                moveTo(8f, 18f)
                horizontalLineTo(21f)
                moveTo(3.5f, 6f)
                horizontalLineTo(3.52f)
                moveTo(3.5f, 12f)
                horizontalLineTo(3.52f)
                moveTo(3.5f, 18f)
                horizontalLineTo(3.52f)
            }
        }
    }

    val CheckSquare: ImageVector by lazy {
        lucideIcon("CheckSquare") {
            strokePath(1.8f) {
                moveTo(9f, 11f)
                lineTo(12f, 14f)
                lineTo(22f, 4f)
                moveTo(21f, 12f)
                verticalLineTo(19f)
                curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                verticalLineTo(5f)
                curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
                horizontalLineTo(16f)
            }
        }
    }

    val Table: ImageVector by lazy {
        lucideIcon("Table") {
            strokePath(1.8f) {
                moveTo(3f, 5f)
                curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
                horizontalLineTo(19f)
                curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
                verticalLineTo(19f)
                curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                close()
                moveTo(3f, 9f)
                horizontalLineTo(21f)
                moveTo(3f, 15f)
                horizontalLineTo(21f)
                moveTo(9f, 3f)
                verticalLineTo(21f)
                moveTo(15f, 3f)
                verticalLineTo(21f)
            }
        }
    }

    val BarChartPoll: ImageVector by lazy {
        lucideIcon("BarChartPoll") {
            strokePath(1.8f) {
                moveTo(18f, 20f)
                verticalLineTo(10f)
                moveTo(12f, 20f)
                verticalLineTo(4f)
                moveTo(6f, 20f)
                verticalLineTo(14f)
            }
        }
    }

    val EyeOff: ImageVector by lazy {
        lucideIcon("EyeOff") {
            strokePath(1.8f) {
                moveTo(17.94f, 17.94f)
                curveTo(16.23f, 19.24f, 14.15f, 20f, 12f, 20f)
                curveTo(5f, 20f, 1f, 12f, 1f, 12f)
                curveTo(2.24f, 9.68f, 3.97f, 7.66f, 6.06f, 6.06f)
                moveTo(9.9f, 4.24f)
                curveTo(10.59f, 4.08f, 11.29f, 4f, 12f, 4f)
                curveTo(19f, 4f, 23f, 12f, 23f, 12f)
                curveTo(22.39f, 13.14f, 21.67f, 14.2f, 20.84f, 15.16f)
                moveTo(1f, 1f)
                lineTo(23f, 23f)
            }
        }
    }

    val Undo: ImageVector by lazy {
        lucideIcon("Undo") {
            strokePath(1.8f) {
                moveTo(3f, 7f)
                verticalLineTo(13f)
                horizontalLineTo(9f)
                moveTo(21f, 17f)
                curveTo(21f, 12.03f, 16.97f, 8f, 12f, 8f)
                curveTo(9.0f, 8f, 6.3f, 9.4f, 4.6f, 11.6f)
                lineTo(3f, 13f)
            }
        }
    }

    val Redo: ImageVector by lazy {
        lucideIcon("Redo") {
            strokePath(1.8f) {
                moveTo(21f, 7f)
                verticalLineTo(13f)
                horizontalLineTo(15f)
                moveTo(3f, 17f)
                curveTo(3f, 12.03f, 7.03f, 8f, 12f, 8f)
                curveTo(15.0f, 8f, 17.7f, 9.4f, 19.4f, 11.6f)
                lineTo(21f, 13f)
            }
        }
    }

    // =========================================================================
    // 8. 通用导航、发现页、消息页与个人中心线性图标 (Chevron / Check / Settings / Ecosystem)
    // =========================================================================

    val ChevronRight: ImageVector by lazy {
        lucideIcon("ChevronRight") {
            strokePath(1.85f) {
                moveTo(9f, 18f)
                lineTo(15f, 12f)
                lineTo(9f, 6f)
            }
        }
    }

    val ChevronDown: ImageVector by lazy {
        lucideIcon("ChevronDown") {
            strokePath(1.85f) {
                moveTo(6f, 9f)
                lineTo(12f, 15f)
                lineTo(18f, 9f)
            }
        }
    }

    val ChevronUp: ImageVector by lazy {
        lucideIcon("ChevronUp") {
            strokePath(1.85f) {
                moveTo(18f, 15f)
                lineTo(12f, 9f)
                lineTo(6f, 15f)
            }
        }
    }

    val Check: ImageVector by lazy {
        lucideIcon("Check") {
            strokePath(1.95f) {
                moveTo(20f, 6f)
                lineTo(9f, 17f)
                lineTo(4f, 12f)
            }
        }
    }

    val Settings: ImageVector by lazy {
        lucideIcon("Settings") {
            strokePath(1.85f) {
                // Center gear circle
                moveTo(15f, 12f)
                curveTo(15f, 13.66f, 13.66f, 15f, 12f, 15f)
                curveTo(10.34f, 15f, 9f, 13.66f, 9f, 12f)
                curveTo(9f, 10.34f, 10.34f, 9f, 12f, 9f)
                curveTo(13.66f, 9f, 15f, 10.34f, 15f, 12f)
                close()
                // True octagon/gear contour (never confused with Sun rays)
                moveTo(10.3f, 3f)
                horizontalLineTo(13.7f)
                lineTo(14.4f, 5.2f)
                lineTo(16.4f, 6.1f)
                lineTo(18.5f, 5.1f)
                lineTo(20.9f, 7.5f)
                lineTo(19.9f, 9.6f)
                lineTo(20.8f, 11.6f)
                lineTo(23f, 12.3f)
                verticalLineTo(11.7f)
                moveTo(10.3f, 3f)
                lineTo(9.6f, 5.2f)
                lineTo(7.6f, 6.1f)
                lineTo(5.5f, 5.1f)
                lineTo(3.1f, 7.5f)
                lineTo(4.1f, 9.6f)
                lineTo(3.2f, 11.6f)
                lineTo(3.2f, 12.4f)
                lineTo(4.1f, 14.4f)
                lineTo(3.1f, 16.5f)
                lineTo(5.5f, 18.9f)
                lineTo(7.6f, 17.9f)
                lineTo(9.6f, 18.8f)
                lineTo(10.3f, 21f)
                horizontalLineTo(13.7f)
                lineTo(14.4f, 18.8f)
                lineTo(16.4f, 17.9f)
                lineTo(18.5f, 18.9f)
                lineTo(20.9f, 16.5f)
                lineTo(19.9f, 14.4f)
                lineTo(20.8f, 12.4f)
                lineTo(20.8f, 11.6f)
            }
        }
    }

    val HistoryClock: ImageVector by lazy {
        lucideIcon("HistoryClock") {
            strokePath(1.8f) {
                moveTo(3f, 12f)
                curveTo(3f, 7.03f, 7.03f, 3f, 12f, 3f)
                curveTo(16.97f, 3f, 21f, 7.03f, 21f, 12f)
                curveTo(21f, 16.97f, 16.97f, 21f, 12f, 21f)
                curveTo(9.3f, 21f, 6.88f, 19.8f, 5.23f, 17.9f)
                moveTo(3f, 7f)
                verticalLineTo(12f)
                horizontalLineTo(8f)
                moveTo(12f, 7f)
                verticalLineTo(12f)
                lineTo(15.5f, 14f)
            }
        }
    }

    val FileText: ImageVector by lazy {
        lucideIcon("FileText") {
            strokePath(1.8f) {
                moveTo(14f, 2f)
                horizontalLineTo(6f)
                curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
                verticalLineTo(20f)
                curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
                horizontalLineTo(18f)
                curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
                verticalLineTo(8f)
                close()
                moveTo(14f, 2f)
                verticalLineTo(8f)
                horizontalLineTo(20f)
                moveTo(8f, 13f)
                horizontalLineTo(16f)
                moveTo(8f, 17f)
                horizontalLineTo(13f)
            }
        }
    }

    val Calendar: ImageVector by lazy {
        lucideIcon("Calendar") {
            strokePath(1.8f) {
                moveTo(5f, 4f)
                horizontalLineTo(19f)
                curveTo(20.1f, 4f, 21f, 4.9f, 21f, 6f)
                verticalLineTo(20f)
                curveTo(21f, 21.1f, 20.1f, 22f, 19f, 22f)
                horizontalLineTo(5f)
                curveTo(3.9f, 22f, 3f, 21.1f, 3f, 20f)
                verticalLineTo(6f)
                curveTo(3f, 4.9f, 3.9f, 4f, 5f, 4f)
                close()
                moveTo(16f, 2f)
                verticalLineTo(6f)
                moveTo(8f, 2f)
                verticalLineTo(6f)
                moveTo(3f, 10f)
                horizontalLineTo(21f)
            }
        }
    }

    val Users: ImageVector by lazy {
        lucideIcon("Users") {
            strokePath(1.8f) {
                moveTo(17f, 21f)
                verticalLineTo(19f)
                curveTo(17f, 16.79f, 15.21f, 15f, 13f, 15f)
                horizontalLineTo(5f)
                curveTo(2.79f, 15f, 1f, 16.79f, 1f, 19f)
                verticalLineTo(21f)
                moveTo(9f, 11f)
                curveTo(11.21f, 11f, 13f, 9.21f, 13f, 7f)
                curveTo(13f, 4.79f, 11.21f, 3f, 9f, 3f)
                curveTo(6.79f, 3f, 5f, 4.79f, 5f, 7f)
                curveTo(5f, 9.21f, 6.79f, 11f, 9f, 11f)
                close()
                moveTo(23f, 21f)
                verticalLineTo(19f)
                curveTo(23f, 17.13f, 21.73f, 15.57f, 20f, 15.13f)
                moveTo(16f, 3.13f)
                curveTo(17.73f, 3.57f, 19f, 5.13f, 19f, 7f)
                curveTo(19f, 8.87f, 17.73f, 10.43f, 16f, 10.87f)
            }
        }
    }

    val AwardBadge: ImageVector by lazy {
        lucideIcon("AwardBadge") {
            strokePath(1.8f) {
                moveTo(12f, 15f)
                curveTo(15.31f, 15f, 18f, 12.31f, 18f, 9f)
                curveTo(18f, 5.69f, 15.31f, 3f, 12f, 3f)
                curveTo(8.69f, 3f, 6f, 5.69f, 6f, 9f)
                curveTo(6f, 12.31f, 8.69f, 15f, 12f, 15f)
                close()
                moveTo(8.21f, 13.89f)
                lineTo(7f, 22f)
                lineTo(12f, 19f)
                lineTo(17f, 22f)
                lineTo(15.79f, 13.88f)
            }
        }
    }

    val Gift: ImageVector by lazy {
        lucideIcon("Gift") {
            strokePath(1.8f) {
                moveTo(20f, 12f)
                verticalLineTo(21f)
                horizontalLineTo(4f)
                verticalLineTo(12f)
                moveTo(2f, 7f)
                horizontalLineTo(22f)
                verticalLineTo(12f)
                horizontalLineTo(2f)
                close()
                moveTo(12f, 21f)
                verticalLineTo(7f)
                moveTo(12f, 7f)
                horizontalLineTo(7.5f)
                curveTo(6.12f, 7f, 5f, 5.88f, 5f, 4.5f)
                curveTo(5f, 3.12f, 6.12f, 2f, 7.5f, 2f)
                curveTo(11f, 2f, 12f, 7f, 12f, 7f)
                moveTo(12f, 7f)
                horizontalLineTo(16.5f)
                curveTo(17.88f, 7f, 19f, 5.88f, 19f, 4.5f)
                curveTo(19f, 3.12f, 17.88f, 2f, 16.5f, 2f)
                curveTo(13f, 2f, 12f, 7f, 12f, 7f)
            }
        }
    }

    val AppsGrid: ImageVector by lazy {
        lucideIcon("AppsGrid") {
            strokePath(1.8f) {
                // Top-left
                moveTo(4f, 4f)
                horizontalLineTo(10f)
                verticalLineTo(10f)
                horizontalLineTo(4f)
                close()
                // Top-right
                moveTo(14f, 4f)
                horizontalLineTo(20f)
                verticalLineTo(10f)
                horizontalLineTo(14f)
                close()
                // Bottom-left
                moveTo(4f, 14f)
                horizontalLineTo(10f)
                verticalLineTo(20f)
                horizontalLineTo(4f)
                close()
                // Bottom-right
                moveTo(14f, 14f)
                horizontalLineTo(20f)
                verticalLineTo(20f)
                horizontalLineTo(14f)
                close()
            }
        }
    }

    val KanbanBoard: ImageVector by lazy {
        lucideIcon("KanbanBoard") {
            strokePath(1.8f) {
                moveTo(4f, 4f)
                horizontalLineTo(20f)
                verticalLineTo(20f)
                horizontalLineTo(4f)
                close()
                moveTo(9f, 4f)
                verticalLineTo(20f)
                moveTo(15f, 4f)
                verticalLineTo(20f)
            }
        }
    }

    val Sparkles: ImageVector by lazy {
        lucideIcon("Sparkles") {
            strokePath(1.8f) {
                moveTo(12f, 3f)
                lineTo(14.2f, 9.8f)
                lineTo(21f, 12f)
                lineTo(14.2f, 14.2f)
                lineTo(12f, 21f)
                lineTo(9.8f, 14.2f)
                lineTo(3f, 12f)
                lineTo(9.8f, 9.8f)
                close()
                moveTo(19f, 3f)
                verticalLineTo(7f)
                moveTo(17f, 5f)
                horizontalLineTo(21f)
            }
        }
    }

    val Layers: ImageVector by lazy {
        lucideIcon("Layers") {
            strokePath(1.8f) {
                moveTo(12f, 2f)
                lineTo(2f, 7f)
                lineTo(12f, 12f)
                lineTo(22f, 7f)
                close()
                moveTo(2f, 12f)
                lineTo(12f, 17f)
                lineTo(22f, 12f)
                moveTo(2f, 17f)
                lineTo(12f, 22f)
                lineTo(22f, 17f)
            }
        }
    }

    val BookOpen: ImageVector by lazy {
        lucideIcon("BookOpen") {
            strokePath(1.8f) {
                moveTo(2f, 4f)
                horizontalLineTo(8f)
                curveTo(10.21f, 4f, 12f, 5.79f, 12f, 8f)
                verticalLineTo(20f)
                curveTo(12f, 18.34f, 10.66f, 17f, 9f, 17f)
                horizontalLineTo(2f)
                close()
                moveTo(22f, 4f)
                horizontalLineTo(16f)
                curveTo(13.79f, 4f, 12f, 5.79f, 12f, 8f)
                verticalLineTo(20f)
                curveTo(12f, 18.34f, 13.34f, 17f, 15f, 17f)
                horizontalLineTo(22f)
                close()
            }
        }
    }

    val Coffee: ImageVector by lazy {
        lucideIcon("Coffee") {
            strokePath(1.8f) {
                moveTo(17f, 8f)
                horizontalLineTo(18f)
                curveTo(20.21f, 8f, 22f, 9.79f, 22f, 12f)
                curveTo(22f, 14.21f, 20.21f, 16f, 18f, 16f)
                horizontalLineTo(17f)
                moveTo(3f, 8f)
                horizontalLineTo(17f)
                verticalLineTo(15f)
                curveTo(17f, 17.76f, 14.76f, 20f, 12f, 20f)
                horizontalLineTo(8f)
                curveTo(5.24f, 20f, 3f, 17.76f, 3f, 15f)
                close()
                moveTo(6f, 2f)
                verticalLineTo(4f)
                moveTo(10f, 2f)
                verticalLineTo(4f)
                moveTo(14f, 2f)
                verticalLineTo(4f)
            }
        }
    }

    val Lock: ImageVector by lazy {
        lucideIcon("Lock") {
            strokePath(1.8f) {
                moveTo(5f, 11f)
                horizontalLineTo(19f)
                curveTo(20.1f, 11f, 21f, 11.9f, 21f, 13f)
                verticalLineTo(20f)
                curveTo(21f, 21.1f, 20.1f, 22f, 19f, 22f)
                horizontalLineTo(5f)
                curveTo(3.9f, 22f, 3f, 21.1f, 3f, 20f)
                verticalLineTo(13f)
                curveTo(3f, 11.9f, 3.9f, 11f, 5f, 11f)
                close()
                moveTo(7f, 11f)
                verticalLineTo(7f)
                curveTo(7f, 4.24f, 9.24f, 2f, 12f, 2f)
                curveTo(14.76f, 2f, 17f, 4.24f, 17f, 7f)
                verticalLineTo(11f)
            }
        }
    }

    val InfoCircle: ImageVector by lazy {
        lucideIcon("InfoCircle") {
            strokePath(1.8f) {
                moveTo(22f, 12f)
                curveTo(22f, 17.52f, 17.52f, 22f, 12f, 22f)
                curveTo(6.48f, 22f, 2f, 17.52f, 2f, 12f)
                curveTo(2f, 6.48f, 6.48f, 2f, 12f, 2f)
                curveTo(17.52f, 2f, 22f, 6.48f, 22f, 12f)
                close()
                moveTo(12f, 16f)
                verticalLineTo(12f)
                moveTo(12f, 8f)
                horizontalLineTo(12.01f)
            }
        }
    }

    val Sun: ImageVector by lazy {
        lucideIcon("Sun") {
            strokePath(1.8f) {
                moveTo(16f, 12f)
                curveTo(16f, 14.21f, 14.21f, 16f, 12f, 16f)
                curveTo(9.79f, 16f, 8f, 14.21f, 8f, 12f)
                curveTo(8f, 9.79f, 9.79f, 8f, 12f, 8f)
                curveTo(14.21f, 8f, 16f, 9.79f, 16f, 12f)
                close()
                moveTo(12f, 2f)
                verticalLineTo(4f)
                moveTo(12f, 20f)
                verticalLineTo(22f)
                moveTo(4.93f, 4.93f)
                lineTo(6.34f, 6.34f)
                moveTo(17.66f, 17.66f)
                lineTo(19.07f, 19.07f)
                moveTo(2f, 12f)
                horizontalLineTo(4f)
                moveTo(20f, 12f)
                horizontalLineTo(22f)
                moveTo(4.93f, 19.07f)
                lineTo(6.34f, 17.66f)
                moveTo(17.66f, 6.34f)
                lineTo(19.07f, 4.93f)
            }
        }
    }

    val Moon: ImageVector by lazy {
        lucideIcon("Moon") {
            strokePath(1.85f) {
                moveTo(21f, 12.79f)
                curveTo(20.42f, 16.49f, 17.24f, 19.32f, 13.4f, 19.32f)
                curveTo(9.15f, 19.32f, 5.68f, 15.85f, 5.68f, 11.6f)
                curveTo(5.68f, 7.76f, 8.51f, 4.58f, 12.21f, 4f)
                curveTo(11.12f, 5.47f, 10.6f, 7.36f, 10.9f, 9.35f)
                curveTo(11.42f, 12.79f, 14.21f, 15.58f, 17.65f, 16.1f)
                curveTo(18.88f, 16.29f, 20.06f, 16.03f, 21f, 15.42f)
                close()
            }
        }
    }

    val LogOut: ImageVector by lazy {
        lucideIcon("LogOut") {
            strokePath(1.85f) {
                moveTo(9f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                verticalLineTo(5f)
                curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
                horizontalLineTo(9f)
                moveTo(16f, 17f)
                lineTo(21f, 12f)
                lineTo(16f, 7f)
                moveTo(21f, 12f)
                horizontalLineTo(9f)
            }
        }
    }

    val Flame: ImageVector by lazy {
        lucideIcon("Flame") {
            strokePath(1.85f) {
                moveTo(8.5f, 14.5f)
                curveTo(8.5f, 16.99f, 10.51f, 19f, 13f, 19f)
                curveTo(16.31f, 19f, 19f, 16.31f, 19f, 13f)
                curveTo(19f, 8.5f, 15f, 5.5f, 13.5f, 2.5f)
                curveTo(12.8f, 5.5f, 10.5f, 7.5f, 10.5f, 10f)
                curveTo(9.1f, 9.4f, 8.2f, 8.2f, 8f, 7f)
                curveTo(6.2f, 9f, 5f, 11.2f, 5f, 13.5f)
                curveTo(5f, 17.64f, 8.36f, 21f, 12.5f, 21f)
            }
        }
    }

    val Palette: ImageVector by lazy {
        lucideIcon("Palette") {
            strokePath(1.85f) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(13.38f, 22f, 14.5f, 20.88f, 14.5f, 19.5f)
                curveTo(14.5f, 18.89f, 14.26f, 18.35f, 13.86f, 17.91f)
                curveTo(13.48f, 17.48f, 13.25f, 16.93f, 13.25f, 16.3f)
                curveTo(13.25f, 14.92f, 14.37f, 13.8f, 15.75f, 13.8f)
                horizontalLineTo(17.5f)
                curveTo(19.99f, 13.8f, 22f, 11.79f, 22f, 9.3f)
                curveTo(22f, 5.27f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(7.5f, 10.5f)
                horizontalLineTo(7.52f)
                moveTo(10.5f, 7.5f)
                horizontalLineTo(10.52f)
                moveTo(14.5f, 7.5f)
                horizontalLineTo(14.52f)
                moveTo(17.5f, 10.5f)
                horizontalLineTo(17.52f)
            }
        }
    }

    val Sliders: ImageVector by lazy {
        lucideIcon("Sliders") {
            strokePath(1.85f) {
                moveTo(4f, 21f)
                verticalLineTo(14f)
                moveTo(4f, 10f)
                verticalLineTo(3f)
                moveTo(12f, 21f)
                verticalLineTo(12f)
                moveTo(12f, 8f)
                verticalLineTo(3f)
                moveTo(20f, 21f)
                verticalLineTo(16f)
                moveTo(20f, 12f)
                verticalLineTo(3f)
                moveTo(1f, 14f)
                horizontalLineTo(7f)
                moveTo(9f, 8f)
                horizontalLineTo(15f)
                moveTo(17f, 16f)
                horizontalLineTo(23f)
            }
        }
    }

    val ExternalLink: ImageVector by lazy {
        lucideIcon("ExternalLink") {
            strokePath(1.85f) {
                moveTo(18f, 13f)
                verticalLineTo(19f)
                curveTo(18f, 20.1f, 17.1f, 21f, 16f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                verticalLineTo(8f)
                curveTo(3f, 6.9f, 3.9f, 6f, 5f, 6f)
                horizontalLineTo(11f)
                moveTo(15f, 3f)
                horizontalLineTo(21f)
                verticalLineTo(9f)
                moveTo(10f, 14f)
                lineTo(21f, 3f)
            }
        }
    }

    val ArrowRight: ImageVector by lazy {
        lucideIcon("ArrowRight") {
            strokePath(2.0f) {
                moveTo(5f, 12f)
                horizontalLineTo(19f)
                moveTo(13f, 6f)
                lineTo(19f, 12f)
                lineTo(13f, 18f)
            }
        }
    }

    val ShieldCheck: ImageVector by lazy {
        lucideIcon("ShieldCheck") {
            strokePath(1.85f) {
                moveTo(12f, 2.5f)
                lineTo(4.5f, 5.8f)
                verticalLineTo(11.4f)
                curveTo(4.5f, 16.5f, 7.7f, 20.5f, 12f, 21.8f)
                curveTo(16.3f, 20.5f, 19.5f, 16.5f, 19.5f, 11.4f)
                verticalLineTo(5.8f)
                close()
                moveTo(9f, 12.2f)
                lineTo(11.1f, 14.3f)
                lineTo(15.2f, 10.2f)
            }
        }
    }

    val WifiSpeed: ImageVector by lazy {
        lucideIcon("WifiSpeed") {
            strokePath(1.85f) {
                moveTo(2.5f, 9f)
                curveTo(7.8f, 4.3f, 16.2f, 4.3f, 21.5f, 9f)
                moveTo(5.8f, 12.5f)
                curveTo(9.3f, 9.4f, 14.7f, 9.4f, 18.2f, 12.5f)
                moveTo(9.2f, 16f)
                curveTo(10.8f, 14.6f, 13.2f, 14.6f, 14.8f, 16f)
                moveTo(12f, 19.2f)
                lineTo(12.01f, 19.2f)
            }
        }
    }

    val BoostRocket: ImageVector by lazy {
        lucideIcon("BoostRocket") {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                moveTo(20.6f, 2.5f)
                curveTo(14.9f, 2.1f, 10.7f, 4.5f, 7.8f, 8.4f)
                lineTo(5.4f, 8.5f)
                curveTo(4.6f, 8.6f, 4.0f, 9.0f, 3.6f, 9.7f)
                lineTo(1.9f, 12.9f)
                curveTo(1.6f, 13.5f, 2.0f, 14.1f, 2.7f, 14.1f)
                lineTo(6.1f, 14.0f)
                lineTo(10.0f, 17.9f)
                lineTo(9.9f, 21.3f)
                curveTo(9.9f, 22.0f, 10.5f, 22.4f, 11.1f, 22.1f)
                lineTo(14.3f, 20.4f)
                curveTo(15.0f, 20.0f, 15.4f, 19.4f, 15.5f, 18.6f)
                lineTo(15.6f, 16.2f)
                curveTo(19.5f, 13.3f, 21.9f, 9.1f, 21.5f, 3.4f)
                curveTo(21.5f, 2.9f, 21.1f, 2.5f, 20.6f, 2.5f)
                close()
                moveTo(15.4f, 6.9f)
                curveTo(16.4f, 6.9f, 17.2f, 7.7f, 17.2f, 8.7f)
                curveTo(17.2f, 9.7f, 16.4f, 10.5f, 15.4f, 10.5f)
                curveTo(14.4f, 10.5f, 13.6f, 9.7f, 13.6f, 8.7f)
                curveTo(13.6f, 7.7f, 14.4f, 6.9f, 15.4f, 6.9f)
                close()
            }
        }
    }

    val Rocket: ImageVector by lazy {
        lucideIcon("Rocket") {
            strokePath(1.8f) {
                // 火箭主舱体 (比例修长对称)
                moveTo(12f, 15f)
                lineTo(9f, 12f)
                curveTo(9.8f, 10.4f, 10.8f, 8.9f, 12f, 7.6f)
                curveTo(14.8f, 4.5f, 18.3f, 2.5f, 21.5f, 2.5f)
                curveTo(21.5f, 5.7f, 19.5f, 9.2f, 16.4f, 12f)
                curveTo(15.1f, 13.2f, 13.6f, 14.2f, 12f, 15f)
                close()
                // 舷窗
                moveTo(15.5f, 8.5f)
                lineTo(15.52f, 8.5f)
                // 左翼与下翼
                moveTo(9f, 12f)
                horizontalLineTo(4.5f)
                curveTo(5f, 9.5f, 6.2f, 8f, 8.2f, 7.5f)
                moveTo(12f, 15f)
                verticalLineTo(19.5f)
                curveTo(14.5f, 19f, 16f, 17.8f, 16.5f, 15.8f)
                // 尾焰
                moveTo(4.5f, 16.5f)
                curveTo(3.2f, 17.8f, 2.8f, 21.2f, 2.8f, 21.2f)
                curveTo(2.8f, 21.2f, 6.2f, 20.8f, 7.5f, 19.5f)
                curveTo(8.3f, 18.7f, 8.3f, 17.3f, 7.5f, 16.5f)
                curveTo(6.7f, 15.7f, 5.3f, 15.7f, 4.5f, 16.5f)
                close()
            }
        }
    }

    val ThumbUp: ImageVector by lazy {
        lucideIcon("ThumbUp") {
            strokePath(1.8f) {
                moveTo(7f, 10f)
                verticalLineTo(20f)
                moveTo(15f, 5.88f)
                lineTo(14f, 10f)
                horizontalLineTo(19.83f)
                curveTo(21.02f, 10f, 21.92f, 11.07f, 21.7f, 12.24f)
                lineTo(20.3f, 19.24f)
                curveTo(20.11f, 20.26f, 19.22f, 21f, 18.18f, 21f)
                horizontalLineTo(5f)
                curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
                verticalLineTo(12f)
                curveTo(3f, 10.9f, 3.9f, 10f, 5f, 10f)
                horizontalLineTo(7.76f)
                curveTo(8.56f, 10f, 9.29f, 9.52f, 9.6f, 8.78f)
                lineTo(12f, 3f)
                curveTo(13.66f, 3f, 15f, 4.34f, 15f, 6f)
                close()
            }
        }
    }

    val Crown: ImageVector by lazy {
        lucideIcon("Crown") {
            strokePath(1.8f) {
                moveTo(3f, 18f)
                horizontalLineTo(21f)
                moveTo(4f, 15f)
                lineTo(2.5f, 6.5f)
                lineTo(8.2f, 10.5f)
                lineTo(12f, 4.5f)
                lineTo(15.8f, 10.5f)
                lineTo(21.5f, 6.5f)
                lineTo(20f, 15f)
                close()
            }
        }
    }

    val Trophy: ImageVector by lazy {
        lucideIcon("Trophy") {
            strokePath(1.8f) {
                moveTo(6f, 9f)
                horizontalLineTo(4.5f)
                curveTo(3.12f, 9f, 2f, 7.88f, 2f, 6.5f)
                verticalLineTo(5f)
                horizontalLineTo(6f)
                moveTo(18f, 9f)
                horizontalLineTo(19.5f)
                curveTo(20.88f, 9f, 22f, 7.88f, 22f, 6.5f)
                verticalLineTo(5f)
                horizontalLineTo(18f)
                moveTo(6f, 3f)
                horizontalLineTo(18f)
                verticalLineTo(9f)
                curveTo(18f, 12.31f, 15.31f, 15f, 12f, 15f)
                curveTo(8.69f, 15f, 6f, 12.31f, 6f, 9f)
                close()
                moveTo(12f, 15f)
                verticalLineTo(20f)
                moveTo(8f, 20f)
                horizontalLineTo(16f)
            }
        }
    }

    val VerifiedBadge: ImageVector by lazy {
        lucideIcon("VerifiedBadge") {
            strokePath(1.8f) {
                moveTo(12f, 2.5f)
                lineTo(14.8f, 4.6f)
                lineTo(18.3f, 4.2f)
                lineTo(19.2f, 7.6f)
                lineTo(21.8f, 10f)
                lineTo(20.5f, 13.3f)
                lineTo(21.5f, 16.7f)
                lineTo(18.3f, 18.4f)
                lineTo(16.8f, 21.5f)
                lineTo(13.3f, 20.8f)
                lineTo(10.7f, 20.8f)
                lineTo(7.2f, 21.5f)
                lineTo(5.7f, 18.4f)
                lineTo(2.5f, 16.7f)
                lineTo(3.5f, 13.3f)
                lineTo(2.2f, 10f)
                lineTo(4.8f, 7.6f)
                lineTo(5.7f, 4.2f)
                lineTo(9.2f, 4.6f)
                close()
                moveTo(9f, 12.2f)
                lineTo(11.1f, 14.3f)
                lineTo(15.3f, 10.1f)
            }
        }
    }

    val Seedling: ImageVector by lazy {
        lucideIcon("Seedling") {
            strokePath(1.8f) {
                moveTo(12f, 21f)
                verticalLineTo(10f)
                moveTo(12f, 14f)
                curveTo(7.5f, 14f, 4f, 10.5f, 4f, 6f)
                curveTo(8.5f, 6f, 12f, 9.5f, 12f, 14f)
                close()
                moveTo(12f, 12f)
                curveTo(16.5f, 12f, 20f, 8.5f, 20f, 4f)
                curveTo(15.5f, 4f, 12f, 7.5f, 12f, 12f)
                close()
            }
        }
    }

    val Bolt: ImageVector by lazy {
        lucideIcon("Bolt") {
            strokePath(1.8f) {
                moveTo(13f, 2f)
                lineTo(4f, 14f)
                horizontalLineTo(11f)
                lineTo(10f, 22f)
                lineTo(20f, 10f)
                horizontalLineTo(13f)
                close()
            }
        }
    }

    val Star: ImageVector by lazy {
        lucideIcon("Star") {
            strokePath(1.8f) {
                moveTo(12f, 2.5f)
                lineTo(14.9f, 8.4f)
                lineTo(21.4f, 9.3f)
                lineTo(16.7f, 13.9f)
                lineTo(17.8f, 20.4f)
                lineTo(12f, 17.3f)
                lineTo(6.2f, 20.4f)
                lineTo(7.3f, 13.9f)
                lineTo(2.6f, 9.3f)
                lineTo(9.1f, 8.4f)
                close()
            }
        }
    }

    val Bug: ImageVector by lazy {
        lucideIcon("Bug") {
            strokePath(1.8f) {
                moveTo(8f, 2f)
                lineTo(9.88f, 3.88f)
                moveTo(16f, 2f)
                lineTo(14.12f, 3.88f)
                moveTo(9f, 7.13f)
                verticalLineTo(6f)
                curveTo(9f, 4.34f, 10.34f, 3f, 12f, 3f)
                curveTo(13.66f, 3f, 15f, 4.34f, 15f, 6f)
                verticalLineTo(7.13f)
                moveTo(12f, 20f)
                curveTo(8.69f, 20f, 6f, 17.31f, 6f, 14f)
                verticalLineTo(10f)
                curveTo(6f, 8.34f, 7.34f, 7f, 9f, 7f)
                horizontalLineTo(15f)
                curveTo(16.66f, 7f, 18f, 8.34f, 18f, 10f)
                verticalLineTo(14f)
                curveTo(18f, 17.31f, 15.31f, 20f, 12f, 20f)
                close()
                moveTo(12f, 20f)
                verticalLineTo(11f)
                moveTo(2f, 13f)
                horizontalLineTo(6f)
                moveTo(18f, 13f)
                horizontalLineTo(22f)
            }
        }
    }

    val Coins: ImageVector by lazy {
        lucideIcon("Coins") {
            strokePath(1.8f) {
                moveTo(9.5f, 15f)
                curveTo(12.54f, 15f, 15f, 12.54f, 15f, 9.5f)
                curveTo(15f, 6.46f, 12.54f, 4f, 9.5f, 4f)
                curveTo(6.46f, 4f, 4f, 6.46f, 4f, 9.5f)
                curveTo(4f, 12.54f, 6.46f, 15f, 9.5f, 15f)
                close()
                moveTo(15.2f, 8.2f)
                curveTo(17.95f, 8.78f, 20f, 11.2f, 20f, 14.1f)
                curveTo(20f, 17.41f, 17.31f, 20.1f, 14f, 20.1f)
                curveTo(11.1f, 20.1f, 8.68f, 18.05f, 8.1f, 15.3f)
            }
        }
    }

    val Eye: ImageVector by lazy {
        lucideIcon("Eye") {
            strokePath(1.8f) {
                moveTo(2f, 12f)
                curveTo(3.8f, 7.5f, 7.5f, 5f, 12f, 5f)
                curveTo(16.5f, 5f, 20.2f, 7.5f, 22f, 12f)
                curveTo(20.2f, 16.5f, 16.5f, 19f, 12f, 19f)
                curveTo(7.5f, 19f, 3.8f, 16.5f, 2f, 12f)
                close()
                moveTo(12f, 15f)
                curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
                curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
                curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
                curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
                close()
            }
        }
    }

    val Share: ImageVector by lazy {
        lucideIcon("Share") {
            strokePath(1.8f) {
                moveTo(18f, 8f)
                curveTo(19.66f, 8f, 21f, 6.66f, 21f, 5f)
                curveTo(21f, 3.34f, 19.66f, 2f, 18f, 2f)
                curveTo(16.34f, 2f, 15f, 3.34f, 15f, 5f)
                curveTo(15f, 6.66f, 16.34f, 8f, 18f, 8f)
                close()
                moveTo(6f, 15f)
                curveTo(7.66f, 15f, 9f, 13.66f, 9f, 12f)
                curveTo(9f, 10.34f, 7.66f, 9f, 6f, 9f)
                curveTo(4.34f, 9f, 3f, 10.34f, 3f, 12f)
                curveTo(3f, 13.66f, 4.34f, 15f, 6f, 15f)
                close()
                moveTo(18f, 22f)
                curveTo(19.66f, 22f, 21f, 20.66f, 21f, 19f)
                curveTo(21f, 17.34f, 19.66f, 16f, 18f, 16f)
                curveTo(16.34f, 16f, 15f, 17.34f, 15f, 19f)
                curveTo(15f, 20.66f, 16.34f, 22f, 18f, 22f)
                close()
                moveTo(8.59f, 13.51f)
                lineTo(15.42f, 17.49f)
                moveTo(15.41f, 6.51f)
                lineTo(8.59f, 10.49f)
            }
        }
    }

    val ShoppingBag: ImageVector by lazy {
        lucideIcon("ShoppingBag") {
            strokePath(1.8f) {
                moveTo(6f, 2f)
                lineTo(3f, 6f)
                verticalLineTo(20f)
                curveTo(3f, 21.1f, 3.9f, 22f, 5f, 22f)
                horizontalLineTo(19f)
                curveTo(20.1f, 22f, 21f, 21.1f, 21f, 20f)
                verticalLineTo(6f)
                lineTo(18f, 2f)
                close()
                moveTo(3f, 6f)
                horizontalLineTo(21f)
                moveTo(16f, 10f)
                curveTo(16f, 12.21f, 14.21f, 14f, 12f, 14f)
                curveTo(9.79f, 14f, 8f, 12.21f, 8f, 10f)
            }
        }
    }

    val Megaphone: ImageVector by lazy {
        lucideIcon("Megaphone") {
            strokePath(1.8f) {
                moveTo(3f, 11f)
                lineTo(21f, 3f)
                verticalLineTo(19f)
                lineTo(3f, 13f)
                close()
                moveTo(6f, 14f)
                verticalLineTo(19f)
                curveTo(6f, 20.1f, 6.9f, 21f, 8f, 21f)
                horizontalLineTo(9.5f)
                curveTo(10.6f, 21f, 11.5f, 20.1f, 11.5f, 19f)
                verticalLineTo(15.8f)
            }
        }
    }

    val CpuChip: ImageVector by lazy {
        lucideIcon("CpuChip") {
            strokePath(1.8f) {
                moveTo(6f, 4f)
                horizontalLineTo(18f)
                curveTo(19.1f, 4f, 20f, 4.9f, 20f, 6f)
                verticalLineTo(18f)
                curveTo(20f, 19.1f, 19.1f, 20f, 18f, 20f)
                horizontalLineTo(6f)
                curveTo(4.9f, 20f, 4f, 19.1f, 4f, 18f)
                verticalLineTo(6f)
                curveTo(4f, 4.9f, 4.9f, 4f, 6f, 4f)
                close()
                moveTo(9f, 9f)
                horizontalLineTo(15f)
                verticalLineTo(15f)
                horizontalLineTo(9f)
                close()
                moveTo(9f, 1f)
                verticalLineTo(4f)
                moveTo(15f, 1f)
                verticalLineTo(4f)
                moveTo(9f, 20f)
                verticalLineTo(23f)
                moveTo(15f, 20f)
                verticalLineTo(23f)
                moveTo(20f, 9f)
                horizontalLineTo(23f)
                moveTo(20f, 14f)
                horizontalLineTo(23f)
                moveTo(1f, 9f)
                horizontalLineTo(4f)
                moveTo(1f, 14f)
                horizontalLineTo(4f)
            }
        }
    }

    val Anchor: ImageVector by lazy {
        lucideIcon("Anchor") {
            strokePath(1.8f) {
                moveTo(12f, 8f)
                curveTo(13.66f, 8f, 15f, 6.66f, 15f, 5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                curveTo(9f, 6.66f, 10.34f, 8f, 12f, 8f)
                close()
                moveTo(12f, 8f)
                verticalLineTo(22f)
                moveTo(5f, 12f)
                horizontalLineTo(2f)
                curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
                curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
                horizontalLineTo(19f)
            }
        }
    }

    val CloudServer: ImageVector by lazy {
        lucideIcon("CloudServer") {
            strokePath(1.8f) {
                moveTo(4f, 4f)
                horizontalLineTo(20f)
                curveTo(21.1f, 4f, 22f, 4.9f, 22f, 6f)
                verticalLineTo(10f)
                curveTo(22f, 11.1f, 21.1f, 12f, 20f, 12f)
                horizontalLineTo(4f)
                curveTo(2.9f, 12f, 2f, 11.1f, 2f, 10f)
                verticalLineTo(6f)
                curveTo(2f, 4.9f, 2.9f, 4f, 4f, 4f)
                close()
                moveTo(4f, 12f)
                horizontalLineTo(20f)
                curveTo(21.1f, 12f, 22f, 12.9f, 22f, 14f)
                verticalLineTo(18f)
                curveTo(22f, 19.1f, 21.1f, 20f, 20f, 20f)
                horizontalLineTo(4f)
                curveTo(2.9f, 20f, 2f, 19.1f, 2f, 18f)
                verticalLineTo(14f)
                curveTo(2f, 12.9f, 2.9f, 12f, 4f, 12f)
                close()
                moveTo(6f, 8f)
                horizontalLineTo(6.02f)
                moveTo(6f, 16f)
                horizontalLineTo(6.02f)
            }
        }
    }

    /**
     * 官方 `Only Apple Can Do` 徽章专属苹果 Logo (`fab fa-apple`) 实心矢量图标
     */
    val AppleLogo: ImageVector by lazy {
        lucideIcon("AppleLogo") {
            path(fill = SolidColor(Color.Black)) {
                // 顶部苹果叶
                moveTo(15.65f, 2.0f)
                curveTo(15.77f, 3.19f, 15.28f, 4.35f, 14.51f, 5.21f)
                curveTo(13.72f, 6.1f, 12.46f, 6.79f, 11.23f, 6.69f)
                curveTo(11.09f, 5.53f, 11.66f, 4.33f, 12.4f, 3.51f)
                curveTo(13.19f, 2.62f, 14.53f, 2.04f, 15.65f, 2.0f)
                close()
                // 苹果主体剪影
                moveTo(19.06f, 16.92f)
                curveTo(18.57f, 18.04f, 18.06f, 18.99f, 17.27f, 19.98f)
                curveTo(16.29f, 21.2f, 15.11f, 22.02f, 13.88f, 22.04f)
                curveTo(12.6f, 22.06f, 11.98f, 21.29f, 10.51f, 21.29f)
                curveTo(9.03f, 21.29f, 8.36f, 22.02f, 7.12f, 22.06f)
                curveTo(5.86f, 22.1f, 4.82f, 21.14f, 3.84f, 19.92f)
                curveTo(2.04f, 17.68f, 0.87f, 13.6f, 2.37f, 10.61f)
                curveTo(3.38f, 8.6f, 5.19f, 7.37f, 7.11f, 7.34f)
                curveTo(8.43f, 7.31f, 9.67f, 8.21f, 10.48f, 8.21f)
                curveTo(11.29f, 8.21f, 12.78f, 7.12f, 14.37f, 7.28f)
                curveTo(15.04f, 7.31f, 16.91f, 7.55f, 18.11f, 9.29f)
                curveTo(18.01f, 9.35f, 15.88f, 10.59f, 15.9f, 13.17f)
                curveTo(15.93f, 16.26f, 18.61f, 17.29f, 19.06f, 16.92f)
                close()
            }
        }
    }

    /**
     * 发送/发布纸飞机矢量图标 (`Send`)
     */
    val Send: ImageVector by lazy {
        lucideIcon("Send") {
            strokePath(1.9f) {
                moveTo(22f, 2f)
                lineTo(11f, 13f)
                moveTo(22f, 2f)
                lineTo(15f, 22f)
                lineTo(11f, 13f)
                lineTo(2f, 9f)
                close()
            }
        }
    }

    // ========================================================================
    // 6. Linux.do Web 官方版块与标签 FontAwesome 6 实心矢量图标集
    // ========================================================================

    private fun faSolidIcon(
        name: String,
        vbWidth: Float,
        vbHeight: Float,
        svgPathData: String
    ): ImageVector {
        val side = maxOf(vbWidth, vbHeight, 512f)
        val dx = (side - vbWidth) / 2f
        val dy = (side - vbHeight) / 2f
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = side,
            viewportHeight = side
        ).apply {
            group(
                name = "${name}_group",
                translationX = dx,
                translationY = dy
            ) {
                addPath(
                    pathData = addPathNodes(svgPathData),
                    fill = SolidColor(Color.Black),
                    pathFillType = PathFillType.NonZero
                )
            }
        }.build()
    }

    val FaCode: ImageVector by lazy {
        faSolidIcon("FaCode", 640f, 512f, "M392.8 1.2c-17-4.9-34.7 5-39.6 22l-128 448c-4.9 17 5 34.7 22 39.6s34.7-5 39.6-22l128-448c4.9-17-5-34.7-22-39.6zm80.6 120.1c-12.5 12.5-12.5 32.8 0 45.3L562.7 256l-89.4 89.4c-12.5 12.5-12.5 32.8 0 45.3s32.8 12.5 45.3 0l112-112c12.5-12.5 12.5-32.8 0-45.3l-112-112c-12.5-12.5-32.8-12.5-45.3 0zm-306.7 0c-12.5-12.5-32.8-12.5-45.3 0l-112 112c-12.5 12.5-12.5 32.8 0 45.3l112 112c12.5 12.5 32.8 12.5 45.3 0s12.5-32.8 0-45.3L77.3 256l89.4-89.4c12.5-12.5 12.5-32.8 0-45.3z")
    }
    val FaSeedling: ImageVector by lazy {
        faSolidIcon("FaSeedling", 512f, 512f, "M512 32c0 113.6-84.6 207.5-194.2 222c-7.1-53.4-30.6-101.6-65.3-139.3C290.8 46.3 364 0 448 0h32c17.7 0 32 14.3 32 32zM0 96C0 78.3 14.3 64 32 64H64c123.7 0 224 100.3 224 224v32V480c0 17.7-14.3 32-32 32s-32-14.3-32-32V320C100.3 320 0 219.7 0 96z")
    }
    val FaShareNodes: ImageVector by lazy {
        faSolidIcon("FaShareNodes", 448f, 512f, "M64 32C28.7 32 0 60.7 0 96V416c0 35.3 28.7 64 64 64H384c35.3 0 64-28.7 64-64V96c0-35.3-28.7-64-64-64H64zM384 160c0 35.3-28.7 64-64 64c-15.4 0-29.5-5.4-40.6-14.5L194.1 256l85.3 46.5c11-9.1 25.2-14.5 40.6-14.5c35.3 0 64 28.7 64 64s-28.7 64-64 64s-64-28.7-64-64c0-2.5 .1-4.9 .4-7.3L174.5 300c-11.7 12.3-28.2 20-46.5 20c-35.3 0-64-28.7-64-64s28.7-64 64-64c18.3 0 34.8 7.7 46.5 20l81.9-44.7c-.3-2.4-.4-4.9-.4-7.3c0-35.3 28.7-64 64-64s64 28.7 64 64z")
    }
    val FaHardDrive: ImageVector by lazy {
        faSolidIcon("FaHardDrive", 512f, 512f, "M0 96C0 60.7 28.7 32 64 32H448c35.3 0 64 28.7 64 64V280.4c-17-15.2-39.4-24.4-64-24.4H64c-24.6 0-47 9.2-64 24.4V96zM64 288H448c35.3 0 64 28.7 64 64v64c0 35.3-28.7 64-64 64H64c-35.3 0-64-28.7-64-64V352c0-35.3 28.7-64 64-64zM320 416a32 32 0 1 0 0-64 32 32 0 1 0 0 64zm128-32a32 32 0 1 0 -64 0 32 32 0 1 0 64 0z")
    }
    val FaBook: ImageVector by lazy {
        faSolidIcon("FaBook", 448f, 512f, "M96 0C43 0 0 43 0 96V416c0 53 43 96 96 96H384h32c17.7 0 32-14.3 32-32s-14.3-32-32-32V384c17.7 0 32-14.3 32-32V32c0-17.7-14.3-32-32-32H384 96zm0 384H352v64H96c-17.7 0-32-14.3-32-32s14.3-32 32-32zm32-240c0-8.8 7.2-16 16-16H336c8.8 0 16 7.2 16 16s-7.2 16-16 16H144c-8.8 0-16-7.2-16-16zm16 48H336c8.8 0 16 7.2 16 16s-7.2 16-16 16H144c-8.8 0-16-7.2-16-16s7.2-16 16-16z")
    }
    val FaBriefcase: ImageVector by lazy {
        faSolidIcon("FaBriefcase", 512f, 512f, "M184 48H328c4.4 0 8 3.6 8 8V96H176V56c0-4.4 3.6-8 8-8zm-56 8V96H64C28.7 96 0 124.7 0 160v96H192 320 512V160c0-35.3-28.7-64-64-64H384V56c0-30.9-25.1-56-56-56H184c-30.9 0-56 25.1-56 56zM512 288H320v32c0 17.7-14.3 32-32 32H224c-17.7 0-32-14.3-32-32V288H0V416c0 35.3 28.7 64 64 64H448c35.3 0 64-28.7 64-64V288z")
    }
    val FaBookOpenReader: ImageVector by lazy {
        faSolidIcon("FaBookOpenReader", 512f, 512f, "M160 96a96 96 0 1 1 192 0A96 96 0 1 1 160 96zm80 152V512l-48.4-24.2c-20.9-10.4-43.5-17-66.8-19.3l-96-9.6C12.5 457.2 0 443.5 0 427V224c0-17.7 14.3-32 32-32H62.3c63.6 0 125.6 19.6 177.7 56zm32 264V248c52.1-36.4 114.1-56 177.7-56H480c17.7 0 32 14.3 32 32V427c0 16.4-12.5 30.2-28.8 31.8l-96 9.6c-23.2 2.3-45.9 8.9-66.8 19.3L272 512z")
    }
    val FaNewspaper: ImageVector by lazy {
        faSolidIcon("FaNewspaper", 512f, 512f, "M96 96c0-35.3 28.7-64 64-64H448c35.3 0 64 28.7 64 64V416c0 35.3-28.7 64-64 64H80c-44.2 0-80-35.8-80-80V128c0-17.7 14.3-32 32-32s32 14.3 32 32V400c0 8.8 7.2 16 16 16s16-7.2 16-16V96zm64 24v80c0 13.3 10.7 24 24 24H296c13.3 0 24-10.7 24-24V120c0-13.3-10.7-24-24-24H184c-13.3 0-24 10.7-24 24zm208-8c0 8.8 7.2 16 16 16h48c8.8 0 16-7.2 16-16s-7.2-16-16-16H384c-8.8 0-16 7.2-16 16zm0 96c0 8.8 7.2 16 16 16h48c8.8 0 16-7.2 16-16s-7.2-16-16-16H384c-8.8 0-16 7.2-16 16zM160 304c0 8.8 7.2 16 16 16H432c8.8 0 16-7.2 16-16s-7.2-16-16-16H176c-8.8 0-16 7.2-16 16zm0 96c0 8.8 7.2 16 16 16H432c8.8 0 16-7.2 16-16s-7.2-16-16-16H176c-8.8 0-16 7.2-16 16z")
    }
    val FaRss: ImageVector by lazy {
        faSolidIcon("FaRss", 448f, 512f, "M0 64C0 46.3 14.3 32 32 32c229.8 0 416 186.2 416 416c0 17.7-14.3 32-32 32s-32-14.3-32-32C384 253.6 226.4 96 32 96C14.3 96 0 81.7 0 64zM0 416a64 64 0 1 1 128 0A64 64 0 1 1 0 416zM32 160c159.1 0 288 128.9 288 288c0 17.7-14.3 32-32 32s-32-14.3-32-32c0-123.7-100.3-224-224-224c-17.7 0-32-14.3-32-32s14.3-32 32-32z")
    }
    val FaPiggyBank: ImageVector by lazy {
        faSolidIcon("FaPiggyBank", 576f, 512f, "M400 96l0 .7c-5.3-.4-10.6-.7-16-.7H256c-16.5 0-32.5 2.1-47.8 6c-.1-2-.2-4-.2-6c0-53 43-96 96-96s96 43 96 96zm-16 32c3.5 0 7 .1 10.4 .3c4.2 .3 8.4 .7 12.6 1.3C424.6 109.1 450.8 96 480 96h11.5c10.4 0 18 9.8 15.5 19.9l-13.8 55.2c15.8 14.8 28.7 32.8 37.5 52.9H544c17.7 0 32 14.3 32 32v96c0 17.7-14.3 32-32 32H512c-9.1 12.1-19.9 22.9-32 32v64c0 17.7-14.3 32-32 32H416c-17.7 0-32-14.3-32-32V448H256v32c0 17.7-14.3 32-32 32H192c-17.7 0-32-14.3-32-32V416c-34.9-26.2-58.7-66.3-63.2-112H68c-37.6 0-68-30.4-68-68s30.4-68 68-68h4c13.3 0 24 10.7 24 24s-10.7 24-24 24H68c-11 0-20 9-20 20s9 20 20 20H99.2c12.1-59.8 57.7-107.5 116.3-122.8c12.9-3.4 26.5-5.2 40.5-5.2H384zm64 136a24 24 0 1 0 -48 0 24 24 0 1 0 48 0z")
    }
    val FaDroplet: ImageVector by lazy {
        faSolidIcon("FaDroplet", 384f, 512f, "M192 512C86 512 0 426 0 320C0 228.8 130.2 57.7 166.6 11.7C172.6 4.2 181.5 0 191.1 0h1.8c9.6 0 18.5 4.2 24.5 11.7C253.8 57.7 384 228.8 384 320c0 106-86 192-192 192zM96 336c0-8.8-7.2-16-16-16s-16 7.2-16 16c0 61.9 50.1 112 112 112c8.8 0 16-7.2 16-16s-7.2-16-16-16c-44.2 0-80-35.8-80-80z")
    }
    val FaHurricane: ImageVector by lazy {
        faSolidIcon("FaHurricane", 384f, 512f, "M0 208C0 104.4 75.7 18.5 174.9 2.6C184 1.2 192 8.6 192 17.9V81.2c0 8.4 6.5 15.3 14.7 16.5C307 112.5 384 199 384 303.4c0 103.6-75.7 189.5-174.9 205.4c-9.2 1.5-17.1-5.9-17.1-15.2V430.2c0-8.4-6.5-15.3-14.7-16.5C77 398.9 0 312.4 0 208zm288 48A96 96 0 1 0 96 256a96 96 0 1 0 192 0zm-96-32a32 32 0 1 1 0 64 32 32 0 1 1 0-64z")
    }
    val FaComments: ImageVector by lazy {
        faSolidIcon("FaComments", 640f, 512f, "M208 352c114.9 0 208-78.8 208-176S322.9 0 208 0S0 78.8 0 176c0 38.6 14.7 74.3 39.6 103.4c-3.5 9.4-8.7 17.7-14.2 24.7c-4.8 6.2-9.7 11-13.3 14.3c-1.8 1.6-3.3 2.9-4.3 3.7c-.5 .4-.9 .7-1.1 .8l-.2 .2 0 0 0 0C1 327.2-1.4 334.4 .8 340.9S9.1 352 16 352c21.8 0 43.8-5.6 62.1-12.5c9.2-3.5 17.8-7.4 25.3-11.4C134.1 343.3 169.8 352 208 352zM448 176c0 112.3-99.1 196.9-216.5 207C255.8 457.4 336.4 512 432 512c38.2 0 73.9-8.7 104.7-23.9c7.5 4 16 7.9 25.2 11.4c18.3 6.9 40.3 12.5 62.1 12.5c6.9 0 13.1-4.5 15.2-11.1c2.1-6.6-.2-13.8-5.8-17.9l0 0 0 0-.2-.2c-.2-.2-.6-.4-1.1-.8c-1-.8-2.5-2-4.3-3.7c-3.6-3.3-8.5-8.1-13.3-14.3c-5.5-7-10.7-15.4-14.2-24.7c24.9-29 39.6-64.7 39.6-103.4c0-92.8-84.9-168.9-192.6-175.5c.4 5.1 .6 10.3 .6 15.5z")
    }
    val FaBullhorn: ImageVector by lazy {
        faSolidIcon("FaBullhorn", 512f, 512f, "M480 32c0-12.9-7.8-24.6-19.8-29.6s-25.7-2.2-34.9 6.9L381.7 53c-48 48-113.1 75-181 75H192 160 64c-35.3 0-64 28.7-64 64v96c0 35.3 28.7 64 64 64l0 128c0 17.7 14.3 32 32 32h64c17.7 0 32-14.3 32-32V352l8.7 0c67.9 0 133 27 181 75l43.6 43.6c9.2 9.2 22.9 11.9 34.9 6.9s19.8-16.6 19.8-29.6V300.4c18.6-8.8 32-32.5 32-60.4s-13.4-51.6-32-60.4V32zm-64 76.7V240 371.3C357.2 317.8 280.5 288 200.7 288H192V192h8.7c79.8 0 156.5-29.8 215.3-83.3z")
    }
    val FaAward: ImageVector by lazy {
        faSolidIcon("FaAward", 384f, 512f, "M173.8 5.5c11-7.3 25.4-7.3 36.4 0L228 17.2c6 3.9 13 5.8 20.1 5.4l21.3-1.3c13.2-.8 25.6 6.4 31.5 18.2l9.6 19.1c3.2 6.4 8.4 11.5 14.7 14.7L344.5 83c11.8 5.9 19 18.3 18.2 31.5l-1.3 21.3c-.4 7.1 1.5 14.2 5.4 20.1l11.8 17.8c7.3 11 7.3 25.4 0 36.4L366.8 228c-3.9 6-5.8 13-5.4 20.1l1.3 21.3c.8 13.2-6.4 25.6-18.2 31.5l-19.1 9.6c-6.4 3.2-11.5 8.4-14.7 14.7L301 344.5c-5.9 11.8-18.3 19-31.5 18.2l-21.3-1.3c-7.1-.4-14.2 1.5-20.1 5.4l-17.8 11.8c-11 7.3-25.4 7.3-36.4 0L156 366.8c-6-3.9-13-5.8-20.1-5.4l-21.3 1.3c-13.2 .8-25.6-6.4-31.5-18.2l-9.6-19.1c-3.2-6.4-8.4-11.5-14.7-14.7L39.5 301c-11.8-5.9-19-18.3-18.2-31.5l1.3-21.3c.4-7.1-1.5-14.2-5.4-20.1L5.5 210.2c-7.3-11-7.3-25.4 0-36.4L17.2 156c3.9-6 5.8-13 5.4-20.1l-1.3-21.3c-.8-13.2 6.4-25.6 18.2-31.5l19.1-9.6C65 70.2 70.2 65 73.4 58.6L83 39.5c5.9-11.8 18.3-19 31.5-18.2l21.3 1.3c7.1 .4 14.2-1.5 20.1-5.4L173.8 5.5zM272 192a80 80 0 1 0 -160 0 80 80 0 1 0 160 0zM1.3 441.8L44.4 339.3c.2 .1 .3 .2 .4 .4l9.6 19.1c11.7 23.2 36 37.3 62 35.8l21.3-1.3c.2 0 .5 0 .7 .2l17.8 11.8c5.1 3.3 10.5 5.9 16.1 7.7l-37.6 89.3c-2.3 5.5-7.4 9.2-13.3 9.7s-11.6-2.2-14.8-7.2L74.4 455.5l-56.1 8.3c-5.7 .8-11.4-1.5-15-6s-4.3-10.7-2.1-16zm248 60.4L211.7 413c5.6-1.8 11-4.3 16.1-7.7l17.8-11.8c.2-.1 .4-.2 .7-.2l21.3 1.3c26 1.5 50.3-12.6 62-35.8l9.6-19.1c.1-.2 .2-.3 .4-.4l43.2 102.5c2.2 5.3 1.4 11.4-2.1 16s-9.3 6.9-15 6l-56.1-8.3-32.2 49.2c-3.2 5-8.9 7.7-14.8 7.2s-11-4.3-13.3-9.7z")
    }
    val FaUsers: ImageVector by lazy {
        faSolidIcon("FaUsers", 640f, 512f, "M144 0a80 80 0 1 1 0 160A80 80 0 1 1 144 0zM512 0a80 80 0 1 1 0 160A80 80 0 1 1 512 0zM0 298.7C0 239.8 47.8 192 106.7 192h42.7c15.9 0 31 3.5 44.6 9.7c-1.3 7.2-1.9 14.7-1.9 22.3c0 38.2 16.8 72.5 43.3 96c-.2 0-.4 0-.7 0H21.3C9.6 320 0 310.4 0 298.7zM405.3 320c-.2 0-.4 0-.7 0c26.6-23.5 43.3-57.8 43.3-96c0-7.6-.7-15-1.9-22.3c13.6-6.3 28.7-9.7 44.6-9.7h42.7C592.2 192 640 239.8 640 298.7c0 11.8-9.6 21.3-21.3 21.3H405.3zM224 224a96 96 0 1 1 192 0 96 96 0 1 1 -192 0zM128 485.3C128 411.7 187.7 352 261.3 352H378.7C452.3 352 512 411.7 512 485.3c0 14.7-11.9 26.7-26.7 26.7H154.7c-14.7 0-26.7-11.9-26.7-26.7z")
    }
    val FaClone: ImageVector by lazy {
        faSolidIcon("FaClone", 512f, 512f, "M288 448H64V224h64V160H64c-35.3 0-64 28.7-64 64V448c0 35.3 28.7 64 64 64H288c35.3 0 64-28.7 64-64V384H288v64zm-64-96H448c35.3 0 64-28.7 64-64V64c0-35.3-28.7-64-64-64H224c-35.3 0-64 28.7-64 64V288c0 35.3 28.7 64 64 64z")
    }
    val FaLock: ImageVector by lazy {
        faSolidIcon("FaLock", 448f, 512f, "M144 144v48H304V144c0-44.2-35.8-80-80-80s-80 35.8-80 80zM80 192V144C80 64.5 144.5 0 224 0s144 64.5 144 144v48h16c35.3 0 64 28.7 64 64V448c0 35.3-28.7 64-64 64H64c-35.3 0-64-28.7-64-64V256c0-35.3 28.7-64 64-64H80z")
    }
    val FaFaucet: ImageVector by lazy {
        faSolidIcon("FaFaucet", 512f, 512f, "M192 96v12L96 96c-17.7 0-32 14.3-32 32s14.3 32 32 32l96-12 31-3.9 1-.1 1 .1 31 3.9 96 12c17.7 0 32-14.3 32-32s-14.3-32-32-32l-96 12V96c0-17.7-14.3-32-32-32s-32 14.3-32 32zM32 256c-17.7 0-32 14.3-32 32v64c0 17.7 14.3 32 32 32H132.1c20.2 29 53.9 48 91.9 48s71.7-19 91.9-48H352c17.7 0 32 14.3 32 32s14.3 32 32 32h64c17.7 0 32-14.3 32-32c0-88.4-71.6-160-160-160H320l-22.6-22.6c-6-6-14.1-9.4-22.6-9.4H256V180.2l-32-4-32 4V224H173.3c-8.5 0-16.6 3.4-22.6 9.4L128 256H32z")
    }
    val FaStore: ImageVector by lazy {
        faSolidIcon("FaStore", 576f, 512f, "M547.6 103.8L490.3 13.1C485.2 5 476.1 0 466.4 0H109.6C99.9 0 90.8 5 85.7 13.1L28.3 103.8c-29.6 46.8-3.4 111.9 51.9 119.4c4 .5 8.1 .8 12.1 .8c26.1 0 49.3-11.4 65.2-29c15.9 17.6 39.1 29 65.2 29c26.1 0 49.3-11.4 65.2-29c15.9 17.6 39.1 29 65.2 29c26.2 0 49.3-11.4 65.2-29c16 17.6 39.1 29 65.2 29c4.1 0 8.1-.3 12.1-.8c55.5-7.4 81.8-72.5 52.1-119.4zM499.7 254.9l-.1 0c-5.3 .7-10.7 1.1-16.2 1.1c-12.4 0-24.3-1.9-35.4-5.3V384H128V250.6c-11.2 3.5-23.2 5.4-35.6 5.4c-5.5 0-11-.4-16.3-1.1l-.1 0c-4.1-.6-8.1-1.3-12-2.3V384v64c0 35.3 28.7 64 64 64H448c35.3 0 64-28.7 64-64V384 252.6c-4 1-8 1.8-12.3 2.3z")
    }
    val FaRocket: ImageVector by lazy {
        faSolidIcon("FaRocket", 512f, 512f, "M156.6 384.9L125.7 354c-8.5-8.5-11.5-20.8-7.7-32.2c3-8.9 7-20.5 11.8-33.8L24 288c-8.6 0-16.6-4.6-20.9-12.1s-4.2-16.7 .2-24.1l52.5-88.5c13-21.9 36.5-35.3 61.9-35.3l82.3 0c2.4-4 4.8-7.7 7.2-11.3C289.1-4.1 411.1-8.1 483.9 5.3c11.6 2.1 20.6 11.2 22.8 22.8c13.4 72.9 9.3 194.8-111.4 276.7c-3.5 2.4-7.3 4.8-11.3 7.2v82.3c0 25.4-13.4 49-35.3 61.9l-88.5 52.5c-7.4 4.4-16.6 4.5-24.1 .2s-12.1-12.2-12.1-20.9V380.8c-14.1 4.9-26.4 8.9-35.7 11.9c-11.2 3.6-23.4 .5-31.8-7.8zM384 168a40 40 0 1 0 0-80 40 40 0 1 0 0 80z")
    }
    val FaCoins: ImageVector by lazy {
        faSolidIcon("FaCoins", 512f, 512f, "M512 80c0 18-14.3 34.6-38.4 48c-29.1 16.1-72.5 27.5-122.3 30.9c-3.7-1.8-7.4-3.5-11.3-5C300.6 137.4 248.2 128 192 128c-8.3 0-16.4 .2-24.5 .6l-1.1-.6C142.3 114.6 128 98 128 80c0-44.2 86-80 192-80S512 35.8 512 80zM160.7 161.1c10.2-.7 20.7-1.1 31.3-1.1c62.2 0 117.4 12.3 152.5 31.4C369.3 204.9 384 221.7 384 240c0 4-.7 7.9-2.1 11.7c-4.6 13.2-17 25.3-35 35.5c0 0 0 0 0 0c-.1 .1-.3 .1-.4 .2l0 0 0 0c-.3 .2-.6 .3-.9 .5c-35 19.4-90.8 32-153.6 32c-59.6 0-112.9-11.3-148.2-29.1c-1.9-.9-3.7-1.9-5.5-2.9C14.3 274.6 0 258 0 240c0-34.8 53.4-64.5 128-75.4c10.5-1.5 21.4-2.7 32.7-3.5zM416 240c0-21.9-10.6-39.9-24.1-53.4c28.3-4.4 54.2-11.4 76.2-20.5c16.3-6.8 31.5-15.2 43.9-25.5V176c0 19.3-16.5 37.1-43.8 50.9c-14.6 7.4-32.4 13.7-52.4 18.5c.1-1.8 .2-3.5 .2-5.3zm-32 96c0 18-14.3 34.6-38.4 48c-1.8 1-3.6 1.9-5.5 2.9C304.9 404.7 251.6 416 192 416c-62.8 0-118.6-12.6-153.6-32C14.3 370.6 0 354 0 336V300.6c12.5 10.3 27.6 18.7 43.9 25.5C83.4 342.6 135.8 352 192 352s108.6-9.4 148.1-25.9c7.8-3.2 15.3-6.9 22.4-10.9c6.1-3.4 11.8-7.2 17.2-11.2c1.5-1.1 2.9-2.3 4.3-3.4V304v5.7V336zm32 0V304 278.1c19-4.2 36.5-9.5 52.1-16c16.3-6.8 31.5-15.2 43.9-25.5V272c0 10.5-5 21-14.9 30.9c-16.3 16.3-45 29.7-81.3 38.4c.1-1.7 .2-3.5 .2-5.3zM192 448c56.2 0 108.6-9.4 148.1-25.9c16.3-6.8 31.5-15.2 43.9-25.5V432c0 44.2-86 80-192 80S0 476.2 0 432V396.6c12.5 10.3 27.6 18.7 43.9 25.5C83.4 438.6 135.8 448 192 448z")
    }
    val FaLightbulb: ImageVector by lazy {
        faSolidIcon("FaLightbulb", 384f, 512f, "M272 384c9.6-31.9 29.5-59.1 49.2-86.2l0 0c5.2-7.1 10.4-14.2 15.4-21.4c19.8-28.5 31.4-63 31.4-100.3C368 78.8 289.2 0 192 0S16 78.8 16 176c0 37.3 11.6 71.9 31.4 100.3c5 7.2 10.2 14.3 15.4 21.4l0 0c19.8 27.1 39.7 54.4 49.2 86.2H272zM192 512c44.2 0 80-35.8 80-80V416H112v16c0 44.2 35.8 80 80 80zM112 176c0 8.8-7.2 16-16 16s-16-7.2-16-16c0-61.9 50.1-112 112-112c8.8 0 16 7.2 16 16s-7.2 16-16 16c-44.2 0-80 35.8-80 80z")
    }
    val FaWater: ImageVector by lazy {
        faSolidIcon("FaWater", 576f, 512f, "M269.5 69.9c11.1-7.9 25.9-7.9 37 0C329 85.4 356.5 96 384 96c26.9 0 55.4-10.8 77.4-26.1l0 0c11.9-8.5 28.1-7.8 39.2 1.7c14.4 11.9 32.5 21 50.6 25.2c17.2 4 27.9 21.2 23.9 38.4s-21.2 27.9-38.4 23.9c-24.5-5.7-44.9-16.5-58.2-25C449.5 149.7 417 160 384 160c-31.9 0-60.6-9.9-80.4-18.9c-5.8-2.7-11.1-5.3-15.6-7.7c-4.5 2.4-9.7 5.1-15.6 7.7c-19.8 9-48.5 18.9-80.4 18.9c-33 0-65.5-10.3-94.5-25.8c-13.4 8.4-33.7 19.3-58.2 25c-17.2 4-34.4-6.7-38.4-23.9s6.7-34.4 23.9-38.4C42.8 92.6 61 83.5 75.3 71.6c11.1-9.5 27.3-10.1 39.2-1.7l0 0C136.7 85.2 165.1 96 192 96c27.5 0 55-10.6 77.5-26.1zm37 288C329 373.4 356.5 384 384 384c26.9 0 55.4-10.8 77.4-26.1l0 0c11.9-8.5 28.1-7.8 39.2 1.7c14.4 11.9 32.5 21 50.6 25.2c17.2 4 27.9 21.2 23.9 38.4s-21.2 27.9-38.4 23.9c-24.5-5.7-44.9-16.5-58.2-25C449.5 437.7 417 448 384 448c-31.9 0-60.6-9.9-80.4-18.9c-5.8-2.7-11.1-5.3-15.6-7.7c-4.5 2.4-9.7 5.1-15.6 7.7c-19.8 9-48.5 18.9-80.4 18.9c-33 0-65.5-10.3-94.5-25.8c-13.4 8.4-33.7 19.3-58.2 25c-17.2 4-34.4-6.7-38.4-23.9s6.7-34.4 23.9-38.4c18.1-4.2 36.2-13.3 50.6-25.2c11.1-9.4 27.3-10.1 39.2-1.7l0 0C136.7 373.2 165.1 384 192 384c27.5 0 55-10.6 77.5-26.1c11.1-7.9 25.9-7.9 37 0zm0-144C329 229.4 356.5 240 384 240c26.9 0 55.4-10.8 77.4-26.1l0 0c11.9-8.5 28.1-7.8 39.2 1.7c14.4 11.9 32.5 21 50.6 25.2c17.2 4 27.9 21.2 23.9 38.4s-21.2 27.9-38.4 23.9c-24.5-5.7-44.9-16.5-58.2-25C449.5 293.7 417 304 384 304c-31.9 0-60.6-9.9-80.4-18.9c-5.8-2.7-11.1-5.3-15.6-7.7c-4.5 2.4-9.7 5.1-15.6 7.7c-19.8 9-48.5 18.9-80.4 18.9c-33 0-65.5-10.3-94.5-25.8c-13.4 8.4-33.7 19.3-58.2 25c-17.2 4-34.4-6.7-38.4-23.9s6.7-34.4 23.9-38.4c18.1-4.2 36.2-13.3 50.6-25.2c11.1-9.5 27.3-10.1 39.2-1.7l0 0C136.7 229.2 165.1 240 192 240c27.5 0 55-10.6 77.5-26.1c11.1-7.9 25.9-7.9 37 0z")
    }
    val FaRobot: ImageVector by lazy {
        faSolidIcon("FaRobot", 640f, 512f, "M320 0c17.7 0 32 14.3 32 32V96H472c39.8 0 72 32.2 72 72V440c0 39.8-32.2 72-72 72H168c-39.8 0-72-32.2-72-72V168c0-39.8 32.2-72 72-72H288V32c0-17.7 14.3-32 32-32zM208 384c-8.8 0-16 7.2-16 16s7.2 16 16 16h32c8.8 0 16-7.2 16-16s-7.2-16-16-16H208zm96 0c-8.8 0-16 7.2-16 16s7.2 16 16 16h32c8.8 0 16-7.2 16-16s-7.2-16-16-16H304zm96 0c-8.8 0-16 7.2-16 16s7.2 16 16 16h32c8.8 0 16-7.2 16-16s-7.2-16-16-16H400zM264 256a40 40 0 1 0 -80 0 40 40 0 1 0 80 0zm152 40a40 40 0 1 0 0-80 40 40 0 1 0 0 80zM48 224H64V416H48c-26.5 0-48-21.5-48-48V272c0-26.5 21.5-48 48-48zm544 0c26.5 0 48 21.5 48 48v96c0 26.5-21.5 48-48 48H576V224h16z")
    }
    val FaCircleQuestion: ImageVector by lazy {
        faSolidIcon("FaCircleQuestion", 512f, 512f, "M256 512A256 256 0 1 0 256 0a256 256 0 1 0 0 512zM169.8 165.3c7.9-22.3 29.1-37.3 52.8-37.3h58.3c34.9 0 63.1 28.3 63.1 63.1c0 22.6-12.1 43.5-31.7 54.8L280 264.4c-.2 13-10.9 23.6-24 23.6c-13.3 0-24-10.7-24-24V250.5c0-8.6 4.6-16.5 12.1-20.8l44.3-25.4c4.7-2.7 7.6-7.7 7.6-13.1c0-8.4-6.8-15.1-15.1-15.1H222.6c-3.4 0-6.4 2.1-7.5 5.3l-.4 1.2c-4.4 12.5-18.2 19-30.6 14.6s-19-18.2-14.6-30.6l.4-1.2zM224 352a32 32 0 1 1 64 0 32 32 0 1 1 -64 0z")
    }
    val FaTree: ImageVector by lazy {
        faSolidIcon("FaTree", 448f, 512f, "M210.6 5.9L62 169.4c-3.9 4.2-6 9.8-6 15.5C56 197.7 66.3 208 79.1 208H104L30.6 281.4c-4.2 4.2-6.6 10-6.6 16C24 309.9 34.1 320 46.6 320H80L5.4 409.5C1.9 413.7 0 419 0 424.5c0 13 10.5 23.5 23.5 23.5H192v32c0 17.7 14.3 32 32 32s32-14.3 32-32V448H424.5c13 0 23.5-10.5 23.5-23.5c0-5.5-1.9-10.8-5.4-15L368 320h33.4c12.5 0 22.6-10.1 22.6-22.6c0-6-2.4-11.8-6.6-16L344 208h24.9c12.7 0 23.1-10.3 23.1-23.1c0-5.7-2.1-11.3-6-15.5L237.4 5.9C234 2.1 229.1 0 224 0s-10 2.1-13.4 5.9z")
    }
    val FaGift: ImageVector by lazy {
        faSolidIcon("FaGift", 512f, 512f, "M190.5 68.8L225.3 128H224 152c-22.1 0-40-17.9-40-40s17.9-40 40-40h2.2c14.9 0 28.8 7.9 36.3 20.8zM64 88c0 14.4 3.5 28 9.6 40H32c-17.7 0-32 14.3-32 32v64c0 17.7 14.3 32 32 32H480c17.7 0 32-14.3 32-32V160c0-17.7-14.3-32-32-32H438.4c6.1-12 9.6-25.6 9.6-40c0-48.6-39.4-88-88-88h-2.2c-31.9 0-61.5 16.9-77.7 44.4L256 85.5l-24.1-41C215.7 16.9 186.1 0 154.2 0H152C103.4 0 64 39.4 64 88zm336 0c0 22.1-17.9 40-40 40H288h-1.3l34.8-59.2C329.1 55.9 342.9 48 357.8 48H360c22.1 0 40 17.9 40 40zM32 288V464c0 26.5 21.5 48 48 48H224V288H32zM288 512H432c26.5 0 48-21.5 48-48V288H288V512z")
    }
    val FaFire: ImageVector by lazy {
        faSolidIcon("FaFire", 448f, 512f, "M159.3 5.4c7.8-7.3 19.9-7.2 27.7 .1c27.6 25.9 53.5 53.8 77.7 84c11-14.4 23.5-30.1 37-42.9c7.9-7.4 20.1-7.4 28 .1c34.6 33 63.9 76.6 84.5 118c20.3 40.8 33.8 82.5 33.8 111.9C448 404.2 348.2 512 224 512C98.4 512 0 404.1 0 276.5c0-38.4 17.8-85.3 45.4-131.7C73.3 97.7 112.7 48.6 159.3 5.4zM225.7 416c25.3 0 47.7-7 68.8-21c42.1-29.4 53.4-88.2 28.1-134.4c-4.5-9-16-9.6-22.5-2l-25.2 29.3c-6.6 7.6-18.5 7.4-24.7-.5c-16.5-21-46-58.5-62.8-79.8c-6.3-8-18.3-8.1-24.7-.1c-33.8 42.5-50.8 69.3-50.8 99.4C112 375.4 162.6 416 225.7 416z")
    }
    val FaFeatherPointed: ImageVector by lazy {
        faSolidIcon("FaFeatherPointed", 512f, 512f, "M278.5 215.6L23 471c-9.4 9.4-9.4 24.6 0 33.9s24.6 9.4 33.9 0l74.8-74.8c7.4 4.6 15.3 8.2 23.8 10.5C200.3 452.8 270 454.5 338 409.4c12.2-8.1 5.8-25.4-8.8-25.4l-16.1 0c-5.1 0-9.2-4.1-9.2-9.2c0-4.1 2.7-7.6 6.5-8.8l97.7-29.3c3.4-1 6.4-3.1 8.4-6.1c4.4-6.4 8.6-12.9 12.6-19.6c6.2-10.3-1.5-23-13.5-23l-38.6 0c-5.1 0-9.2-4.1-9.2-9.2c0-4.1 2.7-7.6 6.5-8.8l80.9-24.3c4.6-1.4 8.4-4.8 10.2-9.3C494.5 163 507.8 86.1 511.9 36.8c.8-9.9-3-19.6-10-26.6s-16.7-10.8-26.6-10C391.5 7 228.5 40.5 137.4 131.6C57.3 211.7 56.7 302.3 71.3 356.4c2.1 7.9 12 9.6 17.8 3.8L253.6 195.8c6.2-6.2 16.4-6.2 22.6 0c5.4 5.4 6.1 13.6 2.2 19.8z")
    }
    val FaLayerGroup: ImageVector by lazy {
        faSolidIcon("FaLayerGroup", 576f, 512f, "M264.5 5.2c14.9-6.9 32.1-6.9 47 0l218.6 101c8.5 3.9 13.9 12.4 13.9 21.8s-5.4 17.9-13.9 21.8l-218.6 101c-14.9 6.9-32.1 6.9-47 0L45.9 149.8C37.4 145.8 32 137.3 32 128s5.4-17.9 13.9-21.8L264.5 5.2zM476.9 209.6l53.2 24.6c8.5 3.9 13.9 12.4 13.9 21.8s-5.4 17.9-13.9 21.8l-218.6 101c-14.9 6.9-32.1 6.9-47 0L45.9 277.8C37.4 273.8 32 265.3 32 256s5.4-17.9 13.9-21.8l53.2-24.6 152 70.2c23.4 10.8 50.4 10.8 73.8 0l152-70.2zm-152 198.2l152-70.2 53.2 24.6c8.5 3.9 13.9 12.4 13.9 21.8s-5.4 17.9-13.9 21.8l-218.6 101c-14.9 6.9-32.1 6.9-47 0L45.9 405.8C37.4 401.8 32 393.3 32 384s5.4-17.9 13.9-21.8l53.2-24.6 152 70.2c23.4 10.8 50.4 10.8 73.8 0z")
    }
    val FaCloud: ImageVector by lazy {
        faSolidIcon("FaCloud", 640f, 512f, "M0 336c0 79.5 64.5 144 144 144H512c70.7 0 128-57.3 128-128c0-61.9-44-113.6-102.4-125.4c4.1-10.7 6.4-22.4 6.4-34.6c0-53-43-96-96-96c-19.7 0-38.1 6-53.3 16.2C367 64.2 315.3 32 256 32C167.6 32 96 103.6 96 192c0 2.7 .1 5.4 .2 8.1C40.2 219.8 0 273.2 0 336z")
    }
    val FaTag: ImageVector by lazy {
        faSolidIcon("FaTag", 448f, 512f, "M0 80V229.5c0 17 6.7 33.3 18.7 45.3l176 176c25 25 65.5 25 90.5 0L418.7 317.3c25-25 25-65.5 0-90.5l-176-176c-12-12-28.3-18.7-45.3-18.7H48C21.5 32 0 53.5 0 80zm112 32a32 32 0 1 1 0 64 32 32 0 1 1 0-64z")
    }
    val FaBolt: ImageVector by lazy {
        faSolidIcon("FaBolt", 448f, 512f, "M349.4 44.6c5.9-13.7 1.5-29.7-10.6-38.5s-28.6-8-39.9 1.8l-256 224c-10 8.8-13.6 22.9-8.9 35.3S50.7 288 64 288H175.5L98.6 467.4c-5.9 13.7-1.5 29.7 10.6 38.5s28.6 8 39.9-1.8l256-224c10-8.8 13.6-22.9 8.9-35.3s-16.6-20.7-30-20.7H272.5L349.4 44.6z")
    }
    val FaStar: ImageVector by lazy {
        faSolidIcon("FaStar", 576f, 512f, "M316.9 18C311.6 7 300.4 0 288.1 0s-23.4 7-28.8 18L195 150.3 51.4 171.5c-12 1.8-22 10.2-25.7 21.7s-.7 24.2 7.9 32.7L137.8 329 113.2 474.7c-2 12 3 24.2 12.9 31.3s23 8 33.8 2.3l128.3-68.5 128.3 68.5c10.8 5.7 23.9 4.9 33.8-2.3s14.9-19.3 12.9-31.3L438.5 329 542.7 225.9c8.6-8.5 11.7-21.2 7.9-32.7s-13.7-19.9-25.7-21.7L381.2 150.3 316.9 18z")
    }
    val FaShieldHalved: ImageVector by lazy {
        faSolidIcon("FaShieldHalved", 512f, 512f, "M256 0c4.6 0 9.2 1 13.4 2.9L457.7 82.8c22 9.3 38.4 31 38.3 57.2c-.5 99.2-41.3 280.7-213.6 363.2c-16.7 8-36.1 8-52.8 0C57.3 420.7 16.5 239.2 16 140c-.1-26.2 16.3-47.9 38.3-57.2L242.7 2.9C246.8 1 251.4 0 256 0zm0 66.8V444.8C394 378 431.1 230.1 432 141.4L256 66.8l0 0z")
    }
    val FaServer: ImageVector by lazy {
        faSolidIcon("FaServer", 512f, 512f, "M64 32C28.7 32 0 60.7 0 96v64c0 35.3 28.7 64 64 64H448c35.3 0 64-28.7 64-64V96c0-35.3-28.7-64-64-64H64zm280 72a24 24 0 1 1 0 48 24 24 0 1 1 0-48zm48 24a24 24 0 1 1 48 0 24 24 0 1 1 -48 0zM64 288c-35.3 0-64 28.7-64 64v64c0 35.3 28.7 64 64 64H448c35.3 0 64-28.7 64-64V352c0-35.3-28.7-64-64-64H64zm280 72a24 24 0 1 1 0 48 24 24 0 1 1 0-48zm56 24a24 24 0 1 1 48 0 24 24 0 1 1 -48 0z")
    }
    val FaFilm: ImageVector by lazy {
        faSolidIcon("FaFilm", 512f, 512f, "M0 96C0 60.7 28.7 32 64 32H448c35.3 0 64 28.7 64 64V416c0 35.3-28.7 64-64 64H64c-35.3 0-64-28.7-64-64V96zM48 368v32c0 8.8 7.2 16 16 16H96c8.8 0 16-7.2 16-16V368c0-8.8-7.2-16-16-16H64c-8.8 0-16 7.2-16 16zm368-16c-8.8 0-16 7.2-16 16v32c0 8.8 7.2 16 16 16h32c8.8 0 16-7.2 16-16V368c0-8.8-7.2-16-16-16H416zM48 240v32c0 8.8 7.2 16 16 16H96c8.8 0 16-7.2 16-16V240c0-8.8-7.2-16-16-16H64c-8.8 0-16 7.2-16 16zm368-16c-8.8 0-16 7.2-16 16v32c0 8.8 7.2 16 16 16h32c8.8 0 16-7.2 16-16V240c0-8.8-7.2-16-16-16H416zM48 112v32c0 8.8 7.2 16 16 16H96c8.8 0 16-7.2 16-16V112c0-8.8-7.2-16-16-16H64c-8.8 0-16 7.2-16 16zM416 96c-8.8 0-16 7.2-16 16v32c0 8.8 7.2 16 16 16h32c8.8 0 16-7.2 16-16V112c0-8.8-7.2-16-16-16H416zM160 128v64c0 17.7 14.3 32 32 32H320c17.7 0 32-14.3 32-32V128c0-17.7-14.3-32-32-32H192c-17.7 0-32 14.3-32 32zm32 160c-17.7 0-32 14.3-32 32v64c0 17.7 14.3 32 32 32H320c17.7 0 32-14.3 32-32V320c0-17.7-14.3-32-32-32H192z")
    }
    val FaGamepad: ImageVector by lazy {
        faSolidIcon("FaGamepad", 640f, 512f, "M192 64C86 64 0 150 0 256S86 448 192 448H448c106 0 192-86 192-192s-86-192-192-192H192zM496 168a40 40 0 1 1 0 80 40 40 0 1 1 0-80zM392 304a40 40 0 1 1 80 0 40 40 0 1 1 -80 0zM168 200c0-13.3 10.7-24 24-24s24 10.7 24 24v32h32c13.3 0 24 10.7 24 24s-10.7 24-24 24H216v32c0 13.3-10.7 24-24 24s-24-10.7-24-24V280H136c-13.3 0-24-10.7-24-24s10.7-24 24-24h32V200z")
    }
    val FaGraduationCap: ImageVector by lazy {
        faSolidIcon("FaGraduationCap", 640f, 512f, "M320 32c-8.1 0-16.1 1.4-23.7 4.1L15.8 137.4C6.3 140.9 0 149.9 0 160s6.3 19.1 15.8 22.6l57.9 20.9C57.3 229.3 48 259.8 48 291.9v28.1c0 28.4-10.8 57.7-22.3 80.8c-6.5 13-13.9 25.8-22.5 37.6C0 442.7-.9 448.3 .9 453.4s6 8.9 11.2 10.2l64 16c4.2 1.1 8.7 .3 12.4-2s6.3-6.1 7.1-10.4c8.6-42.8 4.3-81.2-2.1-108.7C90.3 344.3 86 329.8 80 316.5V291.9c0-30.2 10.2-58.7 27.9-81.5c12.9-15.5 29.6-28 49.2-35.7l157-61.7c8.2-3.2 17.5 .8 20.7 9s-.8 17.5-9 20.7l-157 61.7c-12.4 4.9-23.3 12.4-32.2 21.6l159.6 57.6c7.6 2.7 15.6 4.1 23.7 4.1s16.1-1.4 23.7-4.1L624.2 182.6c9.5-3.4 15.8-12.5 15.8-22.6s-6.3-19.1-15.8-22.6L343.7 36.1C336.1 33.4 328.1 32 320 32zM128 408c0 35.3 86 72 192 72s192-36.7 192-72L496.7 262.6 354.5 314c-11.1 4-22.8 6-34.5 6s-23.5-2-34.5-6L143.3 262.6 128 408z")
    }
}
