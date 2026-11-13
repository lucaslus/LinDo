package do_.linux.lindo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import do_.linux.lindo.core.model.FlairInfo
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.model.PostReactionItem
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopInlineReplyPreviewTest {
    @Test
    fun rendersEditorialInlineRepliesInLightAndDark() {
        val repliedPost = PostItemModel(
            id = 109,
            postNumber = 9,
            userId = 2009,
            username = "king52311",
            cookedHtml = "<p>就全栈还有可能接着裁员，不只是技术部门，我们公司现在搞全栈降本增效</p>",
            createdAtText = "7d"
        )
        val mainPost = PostItemModel(
            id = 112,
            postNumber = 12,
            userId = 1001,
            username = "Jedilus",
            userTitle = "活跃用户",
            flair = FlairInfo(name = "flame"),
            replyToPostNumber = 9,
            replyToUsername = "king52311",
            cookedHtml = "<p>也要看公司了。毕竟存量市场就这么些，公司没有增长点只能降本增效，如果公司业务有增量的，那么不说招人，至少保证稳定或者很小幅的裁员。要是一直盯这存量的业务，没有增量市场，那肯定是一直剪裁到合适为止了。而且公司规模本身非常大的话，那不只是ai来了之后，ai 普及之前也是裁员很厉害的。</p>",
            createdAtText = "7d",
            reactions = listOf(PostReactionItem(id = "heart", emoji = "❤️", count = 1))
        )
        val childReply1 = PostItemModel(
            id = 116,
            postNumber = 16,
            userId = 2009,
            username = "king52311",
            cookedHtml = "<p>是的，我看到个帖子说，公司打着AI的旗号，裁员降薪，到年底也没提升啥效率，就停了，但是人还是裁了，工作量也上来了</p>",
            createdAtText = "7d"
        )
        val nextFloorOp = PostItemModel(
            id = 113,
            postNumber = 13,
            userId = 3001,
            username = "chenmuyu",
            isTopicOwner = true,
            cookedHtml = "<p>确实，现在大环境大家都在收缩非核心业务线。</p>",
            createdAtText = "7d"
        )

        val previewDir = (System.getenv("LINDO_PREVIEW_DIR") ?: System.getProperty("lindo.preview.dir"))
            ?.takeIf { it.isNotBlank() }?.let(::File)

        fun renderSample(isDark: Boolean, expanded: Boolean, outName: String) {
            val palette = buildLinDoPalette(isDark = isDark, option = BrandThemeOption.OFFICIAL_GOLD)
            val widthPx = 1170 // 390dp * 3x Retina
            val heightPx = if (expanded) 1620 else 1180
            val scene = ImageComposeScene(
                width = widthPx,
                height = heightPx,
                density = Density(3f)
            ) {
                CompositionLocalProvider(
                    LocalPalette provides palette,
                    LocalLinDoDarkTheme provides isDark,
                    LocalLinDoAnimations provides false,
                    LocalCurrentAccountUsername provides "Jedilus"
                ) {
                    MaterialTheme {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.cardSurface)
                        ) {
                            CommunityPostFloor(
                                post = mainPost,
                                repliedPost = repliedPost,
                                childReplies = listOf(childReply1),
                                initiallyExpandInlineReplies = expanded,
                                opUsername = "chenmuyu",
                                fontScale = 1f,
                                enableAnimations = false,
                                onInspectBadge = {},
                                onInspectUser = {},
                                onReplyFloor = {},
                                onQuoteFloor = { _, _ -> },
                                onRequestLogin = {}
                            )
                            CommunityPostFloor(
                                post = nextFloorOp,
                                opUsername = "chenmuyu",
                                fontScale = 1f,
                                enableAnimations = false,
                                onInspectBadge = {},
                                onInspectUser = {},
                                onReplyFloor = {},
                                onQuoteFloor = { _, _ -> },
                                onRequestLogin = {}
                            )
                        }
                    }
                }
            }
            try {
                // Render twice so produceState / AST cache settles synchronously
                scene.render(0L)
                Thread.sleep(80L)
                val skiaImg = scene.render(16_000_000L)
                val awtImg = skiaImg.toComposeImageBitmap().toAwtImage()
                assertTrue(awtImg.width == widthPx && awtImg.height == heightPx)
                if (previewDir != null) {
                    previewDir.mkdirs()
                    ImageIO.write(awtImg, "png", File(previewDir, outName))
                }
            } finally {
                scene.close()
            }
        }

        renderSample(isDark = false, expanded = true, outName = "inline_reply_light_expanded.png")
        renderSample(isDark = false, expanded = false, outName = "inline_reply_light_collapsed.png")
        renderSample(isDark = true, expanded = true, outName = "inline_reply_dark_expanded.png")
    }
}
