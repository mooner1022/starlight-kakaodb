package dev.mooner.kakaodb.sharedd;

import android.util.Log;

import dev.mooner.kakaodb.rootd.RootDaemon;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What {@link SharedService} has done in this process, for the status screen ({@link MainActivity} runs in the same
 * process). Starts over whenever Android restarts the process.
 */
final class HelperState {

    static final String TAG = "kakaodb-sharedd";

    private HelperState() {}

    /** Sessions and watches being served now. */
    static final AtomicInteger sessions = new AtomicInteger();

    /** When StarLight last opened a session; 0 if it hasn't. */
    static volatile long lastConnectedAt;

    /** Why the last refused caller was refused, and when; null if none was. */
    static volatile String lastRefusal;
    static volatile long lastRefusedAt;

    private static boolean sqlCipherTried;
    private static String sqlCipherError;

    static void connected() {
        lastConnectedAt = System.currentTimeMillis();
    }

    static void refused(String reason) {
        lastRefusal = reason;
        lastRefusedAt = System.currentTimeMillis();
        Log.w(TAG, "refused a connection: " + reason);
    }

    /**
     * Loads SQLCipher (for crypto_user_database) once. Returns null when it loaded, else why not: the rest of the
     * helper works without it.
     */
    static synchronized String loadSqlCipher() {
        if (!sqlCipherTried) {
            sqlCipherTried = true;
            try {
                RootDaemon.loadInstalledNativeLibrary();
            } catch (LinkageError e) {
                sqlCipherError = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.w(TAG, "SQLCipher unavailable", e);
            }
        }
        return sqlCipherError;
    }
}
