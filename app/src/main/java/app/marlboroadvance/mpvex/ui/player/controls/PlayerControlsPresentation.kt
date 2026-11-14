package app.marlboroadvance.mpvex.ui.player.controls

/** Which queue-bound transport controls should be rendered for the current item. */
internal data class QueueNavigationVisibility(
  val previous: Boolean,
  val next: Boolean,
)

/**
 * Derives transport visibility from real queue boundaries, independently of repeat behavior.
 * Invalid or non-navigable queue positions expose no transport actions.
 */
internal fun queueNavigationVisibility(queueSize: Int, currentIndex: Int): QueueNavigationVisibility {
  if (queueSize <= 1 || currentIndex !in 0 until queueSize) {
    return QueueNavigationVisibility(previous = false, next = false)
  }
  return QueueNavigationVisibility(
    previous = currentIndex > 0,
    next = currentIndex < queueSize - 1,
  )
}

/** The versions submenu is useful even when it contains only the currently selected version. */
internal fun isVersionsRowEnabled(versions: List<*>): Boolean = versions.isNotEmpty()
