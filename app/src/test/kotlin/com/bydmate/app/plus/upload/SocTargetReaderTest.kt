package com.bydmate.app.plus.upload

import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocTargetReaderTest {

    private val helper = mockk<HelperClient>()

    private fun reply(vararg r: Pair<Int, Int>?) {
        coEvery { helper.readBatch(any()) } returns r.toList().takeIf { null !in it }?.filterNotNull()
    }

    @Test fun `it reads the setting fid with a getInt and nothing else`() = runBlocking {
        reply(0 to 43)
        assertEquals(43, SocTargetReader(helper).read())
        coVerify(exactly = 1) { helper.readBatch(listOf(BatchReadItem(5, 1023, 873463848))) }
    }

    @Test fun `a refused read, a sentinel or an unreachable daemon is no target`() = runBlocking {
        reply(-10011 to 43)
        assertNull(SocTargetReader(helper).read())
        reply(0 to 65535)
        assertNull(SocTargetReader(helper).read())
        reply(0 to -1)
        assertNull(SocTargetReader(helper).read())
        coEvery { helper.readBatch(any()) } returns null
        assertNull(SocTargetReader(helper).read())
        coEvery { helper.readBatch(any()) } returns listOf(0 to 1, 0 to 2)
        assertNull(SocTargetReader(helper).read())
    }

    @Test fun `the limits of the percent range are accepted`() = runBlocking {
        reply(0 to 0)
        assertEquals(0, SocTargetReader(helper).read())
        reply(0 to 100)
        assertEquals(100, SocTargetReader(helper).read())
    }
}
