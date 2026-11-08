package dev.mooner.kakaodb.setup

import dev.mooner.kakaodb.ConnectionState
import dev.mooner.kakaodb.db.AccessMode

/**
 * When the plugin asks StarLight for its initial setup, and what the answers mean.
 *
 * Free of StarLight types so it can be tested: the plugin hands in what it reads from its config, its connection state
 * and the setup dialog.
 */
internal object InitialSetup {

    /**
     * Whether the plugin needs its setup. StarLight asks on the main thread, whenever it draws the plugin list, so this
     * only looks at values the plugin already holds.
     *
     * A plugin that is off needs nothing. One that is on needs it when it can't read KakaoTalk ([ConnectionState.Failed]),
     * or when it was never told how ([configured]) and isn't connected: someone who reads the files directly without
     * ever touching the access mode isn't asked while that works.
     */
    fun isRequired(enabled: Boolean, configured: Boolean, state: ConnectionState): Boolean =
        enabled && when (state) {
            is ConnectionState.Failed                                   -> true
            is ConnectionState.Running, is ConnectionState.Reconnecting -> false
            ConnectionState.Starting, ConnectionState.Stopped           -> !configured
        }

    /**
     * What the dialog says at its top: nothing in particular for the first setup (StarLight shows its own text), and
     * what went wrong after that. StarLight remembers a dialog the user closed by this text, so the same failure
     * doesn't ask again.
     */
    fun reason(configured: Boolean, state: ConnectionState): String? =
        if (configured) (state as? ConnectionState.Failed)?.reason else null

    /** What the plugin reads KakaoTalk with: the dialog's items. */
    data class Answers(val mode: AccessMode, val path: String)

    /**
     * Reads the dialog's values. StarLight leaves an item out of them until the user touches it, and the dialog showed
     * [current] for it, so an item that is absent keeps what is in effect.
     */
    fun answers(modeKey: String?, path: String?, current: Answers): Answers =
        Answers(
            mode = AccessMode.entries.firstOrNull { it.key == modeKey } ?: current.mode,
            path = path?.trim()?.takeIf(String::isNotEmpty) ?: current.path,
        )
}
