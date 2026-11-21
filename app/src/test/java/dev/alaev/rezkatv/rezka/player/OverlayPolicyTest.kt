package dev.alaev.rezkatv.rezka.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class OverlayPolicyTest {

    @Test
    fun keepsOverlayVisibleWhilePanelIsOpen() {
        val actual = OverlayPolicy.onPanel(open = true)
        assertThat("открытая панель держит оверлей", actual, equalTo(OverlayAction.SHOW))
    }

    @Test
    fun leavesOverlayToLeanbackWithoutPanel() {
        val actual = OverlayPolicy.onPanel(open = false)
        assertThat("без панели оверлеем управляет leanback", actual, equalTo(OverlayAction.KEEP))
    }
}
