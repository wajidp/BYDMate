package com.bydmate.app.plus.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadUuidTest {

    // Expected values from Python's uuid.uuid5 with the spec's namespace.
    @Test fun `car-log trip id matches an independent uuid5`() {
        assertEquals("b15b94ac-f59f-5d39-9f75-199610db33ab", UploadUuid.trip(12345L).toString())
    }

    @Test fun `idle drain id matches an independent uuid5`() {
        assertEquals("f6f3ab28-6f3f-54f6-9d46-b1937ee28e9c", UploadUuid.idleDrain(1729500000000L).toString())
    }

    @Test fun `same name gives the same id`() {
        assertEquals(UploadUuid.trip(7L), UploadUuid.trip(7L))
    }
}
