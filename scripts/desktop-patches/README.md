# macOS Compose mouse input patch

The installed Compose UI 1.9.3 mouse bridge calls AWT's locking-key getter for
each mouse event. On macOS, the Caps Lock getter synchronously waits for AppKit.
On the investigated Intel Mac this occupied 4,941 / 18,522 UI-thread samples
during a 30-second interaction recording (sampling share, not CPU or dropped frames).

`buildSrc/src/main/java/do_/linux/lindo/buildlogic/MacComposeInputPatch.java` produces a derived dependency JAR through Gradle artifact transforms.
It changes only the private `(I)Z getLockingKeyStateSafe` method to call
`MacKeyboardState`. That helper reads the current combined-session Caps Lock flag
using CoreGraphics, without polling or caching. Other locking keys and fallback
behavior continue through AWT. Normal keyboard events, IME, mouse coordinates,
buttons and all other modifiers are unchanged.

Only the **macOS desktop compile/runtime and desktop test classpaths** use the derived JAR.
Android dependencies and compiled code use the original artifacts. The original dependency metadata is preserved; the native packager receives the same patched artifact from both compile and runtime configurations. The patch
checks the SHA-256 of the original class and refuses an unreviewed dependency
upgrade. All other class bytes, resources and upstream license entries are copied
unchanged. No downloaded upstream Gradle cache files are edited and no patched binaries are committed.

When upgrading Compose, check whether upstream has fixed this path. Remove the
patch/configuration/helper if it has; otherwise re-review the method before
changing the checksum. Do not disable the check or return a constant lock state.

References:

- OpenJDK: `src/java.desktop/macosx/native/libawt_lwawt/awt/LWCToolkit.m`,
  `Java_sun_lwawt_macosx_LWCToolkit_isCapsLockOn`.
- Apple: `CGEventSourceFlagsState(kCGEventSourceStateCombinedSessionState)` and
  `kCGEventFlagMaskAlphaShift` (bit 16).

Build the macOS release with `:composeApp:createReleaseDistributable`. The native
application is self-contained; a system Java install is not required. This task
keeps macOS ProGuard disabled so the tested dependency transformation and native
JNA entry points are preserved. Android's build configuration is unchanged.
`:composeApp:desktopTest --tests '*MacInputRegressionTest'`
checks the real patched classpath, live native state, trackpad routing, and
conservation of fast wheel input on both axes.
