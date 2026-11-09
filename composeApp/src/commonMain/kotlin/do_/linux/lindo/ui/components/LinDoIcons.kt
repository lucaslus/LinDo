package do_.linux.lindo.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
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
}
