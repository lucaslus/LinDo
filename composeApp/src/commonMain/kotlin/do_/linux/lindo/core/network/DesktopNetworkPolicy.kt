package do_.linux.lindo.core.network

/** macOS browser verification state, independent of shared Android/Mac read recovery. */
object DesktopNetworkPolicy {
    @Volatile var enabled = false
    @Volatile var verifying = false
}
