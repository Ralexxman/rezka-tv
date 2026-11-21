package dev.alaev.rezkatv.rezka.player

enum class OverlayAction { KEEP, SHOW }

object OverlayPolicy {

    fun onPanel(open: Boolean) = if (open) OverlayAction.SHOW else OverlayAction.KEEP
}
