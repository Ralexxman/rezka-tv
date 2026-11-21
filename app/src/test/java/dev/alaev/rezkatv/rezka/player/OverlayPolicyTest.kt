package dev.alaev.rezkatv.rezka.player

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class OverlayPolicyTest {

    @Test
    fun keepsOverlayVisibleWhilePanelIsOpen() {
        assertThat("открытая панель держит оверлей", OverlayPolicy.onPanel(open = true), equalTo(OverlayAction.SHOW))
    }

    @Test
    fun leavesOverlayToLeanbackWithoutPanel() {
        assertThat(
            "без панели оверлеем управляет leanback",
            OverlayPolicy.onPanel(open = false),
            equalTo(OverlayAction.KEEP),
        )
    }
}
