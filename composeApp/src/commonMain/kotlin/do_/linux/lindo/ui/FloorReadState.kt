package do_.linux.lindo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import do_.linux.lindo.core.connect.ScreenTrackEngine
import do_.linux.lindo.core.model.PostItemModel
import do_.linux.lindo.core.network.LinuxDoRepository

/** Only a viewed floor fades locally; feed/server read progress still requires acknowledgement. */
@Composable
internal fun rememberFloorUnread(topicId: Int, post: PostItemModel, isAuthorized: Boolean): Boolean {
    val localVersion = ScreenTrackEngine.localReadVersion
    val confirmedVersion = ScreenTrackEngine.serverConfirmedReadVersion
    val repositoryVersion = LinuxDoRepository.readTopicsVersion
    return isAuthorized && !post.isRead && remember(topicId, post.postNumber, localVersion, confirmedVersion, repositoryVersion) {
        !ScreenTrackEngine.isPostViewedLocally(topicId, post.postNumber) &&
            !ScreenTrackEngine.isPostConfirmedReadByServer(topicId, post.postNumber) &&
            (LinuxDoRepository.getServerConfirmedReadFloor(topicId) ?: 0) < post.postNumber
    }
}
