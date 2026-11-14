package dev.mooner.kakaodb

/**
 * When a connection that couldn't be made is tried again without anyone asking.
 *
 * What stops a connection often passes by itself: after a reboot StarLight may start before KakaoTalk has opened its
 * database, or before the shared UID helper can be bound. A bot nobody watches would otherwise stay down until someone
 * pressed "Reconnect". Failures that need the user (su denied or missing, see [dev.mooner.kakaodb.db.AccessResult.Failed])
 * aren't tried again.
 *
 * Free of StarLight types so it can be tested.
 */
internal object ConnectRetry {

    const val FIRST_DELAY_MILLIS = 5_000L

    /** Root mode starts its helper with su on a retry this far apart (the helper has exited by then): not too often. */
    const val MAX_DELAY_MILLIS = 5 * 60_000L

    /**
     * How long to wait before the next attempt, after [failures] failed ones in a row (1 for the first): 5 seconds,
     * doubling each time up to 5 minutes.
     */
    fun delayMillis(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, MAX_DOUBLINGS)
        return (FIRST_DELAY_MILLIS shl doublings).coerceAtMost(MAX_DELAY_MILLIS)
    }

    /** Enough to pass [MAX_DELAY_MILLIS], and few enough not to overflow. */
    private const val MAX_DOUBLINGS = 16
}
