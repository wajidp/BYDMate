package com.bydmate.app.plus.units

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.data.telegram.ReportInputs
import com.bydmate.app.data.telegram.ReportStrings
import com.bydmate.app.data.telegram.TelegramReportBuilder
import com.bydmate.app.util.localizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlusTyreUnitTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun tires(psi: Boolean, lang: String = "en"): String {
        val lc = ctx.localizedContext(lang)
        val data = diParsData(tirePressFL = 240, tirePressFR = 240, tirePressRL = 230, tirePressRR = 0)
        val inputs = ReportInputs(data, null, null, null, null, null, tyrePsi = psi)
        return TelegramReportBuilder.build(
            TelegramReportBuilder.ruleHeader("Shutdown"), "", setOf(ReportField.TIRES), inputs, lang,
            ReportStrings { id, args -> lc.getString(id, *args) }, 0L,
        ).text.lines().first { it.startsWith("🛞") }
    }

    @Test fun `psi with one decimal, a wheel without a reading keeps its dash`() {
        assertEquals("🛞 Tires: FL 34.8 · FR 34.8 · RL 33.4 · RR - psi", tires(psi = true))
        assertEquals("🛞 Шины: ПЛ 34,8 · ПП 34,8 · ЗЛ 33,4 · ЗП - psi", tires(psi = true, lang = "ru"))
    }

    @Test fun `switched off it is bar, as upstream`() {
        assertEquals("🛞 Tires: FL 2.4 · FR 2.4 · RL 2.3 · RR - bar", tires(psi = false))
    }

    @Test fun `psi is the default for the owner`() {
        ctx.getSharedPreferences(PlusTyreUnit.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        assertTrue(PlusTyreUnit.psi(ctx))
    }
}
