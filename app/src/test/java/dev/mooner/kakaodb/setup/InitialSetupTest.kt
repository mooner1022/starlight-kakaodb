package dev.mooner.kakaodb.setup

import dev.mooner.kakaodb.ConnectionState
import dev.mooner.kakaodb.db.AccessMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitialSetupTest {

    private val running = ConnectionState.Running(selfId = 1, selfName = "me", since = 0, warnings = emptyList())
    private val reconnecting = ConnectionState.Reconnecting(reason = "lost", retryAt = 0)
    private val failed = ConnectionState.Failed("Can't access /data/data/com.kakao.talk/databases: permission denied")

    @Test
    fun aFailedConnectionNeedsTheSetupWhetherOrNotItWasConfigured() {
        assertTrue(InitialSetup.isRequired(enabled = true, configured = false, state = failed))
        assertTrue(InitialSetup.isRequired(enabled = true, configured = true, state = failed))
    }

    @Test
    fun anUnconfiguredPluginNeedsItUntilItIsConnected() {
        assertTrue(InitialSetup.isRequired(enabled = true, configured = false, state = ConnectionState.Stopped))
        assertTrue(InitialSetup.isRequired(enabled = true, configured = false, state = ConnectionState.Starting))
        // Reading the files directly without ever choosing a mode works: nothing to ask.
        assertFalse(InitialSetup.isRequired(enabled = true, configured = false, state = running))
        assertFalse(InitialSetup.isRequired(enabled = true, configured = false, state = reconnecting))
    }

    @Test
    fun aConfiguredPluginIsNotAskedWhileItIsNotFailing() {
        for (state in listOf(ConnectionState.Stopped, ConnectionState.Starting, running, reconnecting))
            assertFalse(InitialSetup.isRequired(enabled = true, configured = true, state = state), state.toString())
    }

    @Test
    fun aPluginThatIsOffNeedsNothing() {
        for (configured in listOf(false, true))
            for (state in listOf(ConnectionState.Stopped, ConnectionState.Starting, running, reconnecting, failed))
                assertFalse(InitialSetup.isRequired(enabled = false, configured = configured, state = state), "$configured $state")
    }

    @Test
    fun theFirstSetupHasNoReasonAndLaterOnesSayWhatFailed() {
        assertNull(InitialSetup.reason(configured = false, state = failed))
        assertNull(InitialSetup.reason(configured = false, state = ConnectionState.Stopped))
        assertEquals((failed as ConnectionState.Failed).reason, InitialSetup.reason(configured = true, state = failed))
        assertNull(InitialSetup.reason(configured = true, state = running))
    }

    @Test
    fun theSameFailureGivesTheSameReason() {
        // StarLight remembers a closed dialog by this text: it must not change from one asking to the next.
        assertEquals(
            InitialSetup.reason(configured = true, state = ConnectionState.Failed("x")),
            InitialSetup.reason(configured = true, state = ConnectionState.Failed("x")),
        )
    }

    private val current = InitialSetup.Answers(AccessMode.ROOT, "/data/user/10/com.kakao.talk/databases")

    @Test
    fun anItemTheUserDidNotTouchKeepsWhatIsInEffect() {
        assertEquals(current, InitialSetup.answers(modeKey = null, path = null, current = current))
    }

    @Test
    fun touchedItemsReplaceWhatIsInEffect() {
        val answers = InitialSetup.answers(modeKey = AccessMode.SHARED.key, path = " /sdcard/db ", current = current)
        assertEquals(InitialSetup.Answers(AccessMode.SHARED, "/sdcard/db"), answers)
    }

    @Test
    fun oneItemCanBeTouchedWithoutTheOther() {
        assertEquals(
            InitialSetup.Answers(AccessMode.DIRECT, current.path),
            InitialSetup.answers(modeKey = AccessMode.DIRECT.key, path = null, current = current),
        )
        assertEquals(
            InitialSetup.Answers(current.mode, "/sdcard/db"),
            InitialSetup.answers(modeKey = null, path = "/sdcard/db", current = current),
        )
    }

    @Test
    fun aBlankFolderOrUnknownModeFallsBackInsteadOfBecomingDirect() {
        assertEquals(current, InitialSetup.answers(modeKey = "su", path = "   ", current = current))
    }
}
