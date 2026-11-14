package dev.mooner.kakaodb.core

data class DecoderOptions(
    /** Directory holding KakaoTalk.db and KakaoTalk2.db. */
    val baseDBPath: String,
    /** The user's own user_id. Inferred from the database when null. */
    val selfId: Long? = null,
    /** The user's own name. The open chat profile nickname is used when null. */
    val selfName: String? = null,
    /** Room cache TTL. Names the user gives rooms change without any signal, so only the TTL refreshes them. */
    val roomCacheTtlMillis: Long = 300_000L,
    /** Maximum chat_logs rows read at once. */
    val batchSize: Int = 500,
    /**
     * Least time between two checks. The first trigger after a quiet spell is checked at once; triggers that come
     * soon after wait up to this long, merged into one. Bounds the checks while KakaoTalk writes nonstop (a sync) to a
     * couple hundred a second; a message committed right after something else may come this much later.
     */
    val minPollSpacingMillis: Long = 5L,
    /**
     * How often, at most, a check that found no new rows also looks whether chat_logs was emptied (its highest _id
     * went back: the database was reset in place). Most commits add no row, and the look is one more query for each;
     * a reset is rare, and is noticed up to this much later, rows added meanwhile going unreported. 0: every time.
     */
    val resetCheckIntervalMillis: Long = 10_000L,
    /** First wait before reopening a connection that broke and couldn't be reopened; doubles on each failure. */
    val reconnectDelayMillis: Long = 2_000L,
    /** Longest wait between attempts to reopen a broken connection. */
    val maxReconnectDelayMillis: Long = 60_000L,
) {
    companion object {
        const val MAIN_DB = "KakaoTalk.db"
        const val SUB_DB  = "KakaoTalk2.db"
        /** SQLCipher-encrypted; holds the friend list of newer KakaoTalk versions. */
        const val USER_DB = "crypto_user_database"
    }
}
