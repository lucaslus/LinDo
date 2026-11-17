package do_.linux.lindo.ui

import androidx.compose.foundation.Indication
import androidx.compose.ui.graphics.Color

internal expect fun platformIndicationOverride(
    isDark: Boolean,
    tint: Color,
    focusRing: Color
): Indication?
