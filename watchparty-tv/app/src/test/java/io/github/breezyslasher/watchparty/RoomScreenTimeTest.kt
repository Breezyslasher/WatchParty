package io.github.breezyslasher.watchparty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Room screen's Go to time field and clock. */
class RoomScreenTimeTest {
    @Test fun parsesTimes() {
        assertEquals(3723.0, RoomScreen.parseTime("1:02:03")!!, 0.0)
        assertEquals(3723.0, RoomScreen.parseTime(" 62:03 ")!!, 0.0)
        assertEquals(90.0, RoomScreen.parseTime("90")!!, 0.0)
        for (bad in listOf("", "1:", "a:10", "1:2:3:4", "-5", "1.5")) assertNull(bad, RoomScreen.parseTime(bad))
    }

    @Test fun formatsTheClock() {
        assertEquals("0:00", RoomScreen.clockText(0.0))
        assertEquals("1:30", RoomScreen.clockText(90.4))
        assertEquals("1:02:03", RoomScreen.clockText(3723.0))
        assertEquals("0:00", RoomScreen.clockText(-4.0))
    }
}
