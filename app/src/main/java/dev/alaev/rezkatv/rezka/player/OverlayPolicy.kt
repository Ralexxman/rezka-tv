package dev.alaev.rezkatv.rezka.player

object OverlayPolicy {

    fun onPanel(open: Boolean) = if (open) OverlayAction.SHOW else OverlayAction.KEEP
}
