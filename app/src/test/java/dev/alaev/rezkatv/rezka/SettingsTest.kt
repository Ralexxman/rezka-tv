package dev.alaev.rezkatv.rezka

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test

class SettingsTest {

    private fun qualities(vararg labels: String) = labels.map { Quality(it, listOf("https://x/$it")) }

    private fun all() = qualities("360p", "480p", "720p", "1080p", "1080p Ultra")

    @Test
    fun picksBestRegularQualityForFullHdScreen() {
        val expected = "1080p"
        val picked = QualityPicker.pick(all(), Settings.AUTO, 1080)?.label
        assertThat("«Авто» на 1080 — лучшее обычное", picked, equalTo(expected))
    }

    @Test
    fun picksQualityNotAboveScreen() {
        val expected = "720p"
        val picked = QualityPicker.pick(all(), Settings.AUTO, 720)?.label
        assertThat("«Авто» не выше экрана", picked, equalTo(expected))
    }

    @Test
    fun skipsUltraInAutoEvenOnLargeScreen() {
        val expected = "1080p"
        val picked = QualityPicker.pick(all(), Settings.AUTO, 2160)?.label
        assertThat("Ultra — только по явному выбору", picked, equalTo(expected))
    }

    @Test
    fun picksExplicitUltra() {
        val expected = "1080p Ultra"
        val picked = QualityPicker.pick(all(), expected, 1080)?.label
        assertThat("явный выбор Ultra соблюдается", picked, equalTo(expected))
    }

    @Test
    fun picksExplicitLowQuality() {
        val expected = "480p"
        val picked = QualityPicker.pick(all(), expected, 1080)?.label
        assertThat("явный выбор ниже экрана соблюдается", picked, equalTo(expected))
    }

    @Test
    fun fallsBackToNearestLowerWhenChoiceIsMissing() {
        val expected = "480p"
        val picked = QualityPicker.pick(qualities("360p", "480p"), "1080p", 1080)?.label
        assertThat("нет выбранного — ближайшее ниже", picked, equalTo(expected))
    }

    @Test
    fun fallsBackFromMissingUltraToRegularFullHd() {
        val expected = "1080p"
        val picked = QualityPicker.pick(qualities("360p", "480p", "720p", "1080p"), "1080p Ultra", 1080)?.label
        assertThat("нет Ultra — обычное 1080p", picked, equalTo(expected))
    }

    @Test
    fun fallsBackToLowestWhenNothingIsLower() {
        val expected = "1080p"
        val picked = QualityPicker.pick(qualities("1080p", "1080p Ultra"), "360p", 1080)?.label
        assertThat("ниже выбранного ничего нет — самое низкое из имеющихся", picked, equalTo(expected))
    }

    @Test
    fun picksBestAvailableRegardlessOfScreenAndUltra() {
        val expected = "2160p"
        val picked = QualityPicker.pick(qualities("360p", "1080p", "1080p Ultra", "2160p"), Settings.BEST, 1080)?.label
        assertThat("«Лучшее доступное» — максимум из списка сайта", picked, equalTo(expected))
    }

    @Test
    fun readsHeightFromFourKLabel() {
        val expected = 2160
        val height = Quality("4K", emptyList()).height
        assertThat("«4K» — это 2160 строк", height, equalTo(expected))
    }

    @Test
    fun picksFourKLabelAsBest() {
        val expected = "4K"
        val picked = QualityPicker.pick(qualities("1080p Ultra", "4K"), Settings.BEST, 1080)?.label
        assertThat("«Лучшее доступное» ставит 4K выше Ultra", picked, equalTo(expected))
    }

    @Test
    fun picksNothingFromEmptyList() {
        val picked = QualityPicker.pick(emptyList(), Settings.AUTO, 1080)
        assertThat("без качеств выбирать нечего", picked, nullValue())
    }

    @Test
    fun highlightsCurrentStreamQuality() {
        val list = all()
        val second = list[1]
        val expected = 2
        assertThat(
            "текущий 480p — вторая строка списка",
            QualityPicker.selectedIndex(list, second, null),
            equalTo(expected),
        )
    }

    @Test
    fun highlightsAutoRowWhenStreamIsScreenBest() {
        val list = all()
        val auto = QualityPicker.pick(list, Settings.AUTO, 1080)
        val expected = 0
        assertThat(
            "поток равен авто-выбору — первая строка",
            QualityPicker.selectedIndex(list, auto, auto),
            equalTo(expected),
        )
    }

    @Test
    fun startsFromBestAvailableQuality() {
        val stored = null
        val migrated = true
        val expected = Settings.BEST
        assertThat("без настройки — лучшее доступное", QualityMigration.value(stored, migrated), equalTo(expected))
    }

    @Test
    fun dropsQualityStoredByOldVersion() {
        val stored = "720p"
        val migrated = false
        val expected = Settings.BEST
        assertThat("старое 720p сбрасывается миграцией", QualityMigration.value(stored, migrated), equalTo(expected))
    }

    @Test
    fun keepsExplicitChoiceAfterMigration() {
        val stored = "480p"
        val migrated = true
        assertThat("явный выбор после миграции остаётся", QualityMigration.value(stored, migrated), equalTo(stored))
    }

    @Test
    fun clearsStoredQualityOnlyOnce() {
        val migrated = true
        val expected = false
        assertThat("миграция одноразовая", QualityMigration.clearsStored(migrated), equalTo(expected))
    }

    @Test
    fun usesTenSecondSeekStepByDefault() {
        val expected = 10_000L
        val step = Settings.seekStepMs(null)
        assertThat("шаг перемотки по умолчанию — 10 с", step, equalTo(expected))
    }

    @Test
    fun convertsSeekStepSettingToMilliseconds() {
        val expected = 30_000L
        val step = Settings.seekStepMs("30")
        assertThat("настройка в секундах", step, equalTo(expected))
    }

    @Test
    fun acceptsShortSeekStep() {
        val expected = 5_000L
        val step = Settings.seekStepMs("5")
        assertThat("короткий шаг 5 с", step, equalTo(expected))
    }

    @Test
    fun ignoresUnparsableSeekStep() {
        val expected = 10_000L
        val step = Settings.seekStepMs("abc")
        assertThat("не число — шаг по умолчанию", step, equalTo(expected))
    }

    @Test
    fun ignoresZeroSeekStep() {
        val expected = 10_000L
        val step = Settings.seekStepMs("0")
        assertThat("ноль — шаг по умолчанию", step, equalTo(expected))
    }
}
