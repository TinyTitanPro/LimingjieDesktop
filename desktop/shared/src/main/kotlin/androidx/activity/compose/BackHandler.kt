package androidx.activity.compose

import androidx.compose.runtime.Composable

/**
 * Desktop windows have no system back gesture, so navigation guards are inert here.
 * The shared screens keep their Android behaviour; this shim only satisfies compilation.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
}
