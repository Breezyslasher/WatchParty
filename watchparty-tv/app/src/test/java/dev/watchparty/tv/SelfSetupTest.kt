package dev.watchparty.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class SelfSetupTest {

    private val listener = "dev.watchparty.tv/dev.watchparty.tv.SessionAccessService"

    @Test fun android9AndUpUsesAllowListener() {
        assertEquals(
            listOf("cmd notification allow_listener $listener"),
            SelfSetup.commands("dev.watchparty.tv", listener, 28, "other/.Svc"),
        )
    }

    @Test fun olderTvsAppendToTheListenerSettingWithoutDroppingOthers() {
        assertEquals(
            "settings put secure enabled_notification_listeners other/.Svc:$listener",
            SelfSetup.commands("dev.watchparty.tv", listener, 27, "other/.Svc")[0],
        )
        assertEquals(
            "settings put secure enabled_notification_listeners $listener",
            SelfSetup.commands("dev.watchparty.tv", listener, 26, null)[0],
        )
    }

    @Test fun portsTryTheConfiguredOneThen5555() {
        assertEquals(listOf(5555), SelfSetup.ports(listOf("", "")))
        assertEquals(listOf(5556, 5555), SelfSetup.ports(listOf("5556\n", "")))
        assertEquals(listOf(5555), SelfSetup.ports(listOf("5555", "5555")))
        assertEquals(listOf(5555), SelfSetup.ports(listOf("-1", "abc")))
    }

    @Test fun failuresSpotErrorOutput() {
        assertEquals(emptyList<String>(), SelfSetup.failures(listOf("", "\n")))
        assertEquals(
            listOf("Exception occurred while executing: java.lang.SecurityException: …"),
            SelfSetup.failures(listOf("", "Exception occurred while executing: java.lang.SecurityException: …")),
        )
        assertEquals(1, SelfSetup.failures(listOf("Unknown command: allow_listener")).size)
    }
}
