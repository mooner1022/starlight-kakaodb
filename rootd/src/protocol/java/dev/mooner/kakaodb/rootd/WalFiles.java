package dev.mooner.kakaodb.rootd;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Whether a KakaoTalk database can be opened read-only without SQLite creating files next to it.
 *
 * <p>A WAL database needs its -wal and -shm files, and SQLite creates them when they're missing, even for a read-only
 * connection, and leaves them behind. Created by the root helper they carry its SELinux label instead of KakaoTalk's,
 * and created by StarLight they belong to StarLight; either way KakaoTalk may then be unable to open its own database.
 * KakaoTalk keeps both files while it has the database open, and recreates them when it opens it, so a database
 * missing either is refused until KakaoTalk has run.
 *
 * <p>Shared by the plugin and the root helper (this source set is compiled into both), so both refuse the same files.
 */
public final class WalFiles {

    private WalFiles() {}

    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    /**
     * What opening {@code db} would create: "-wal", "-shm" or "-wal, -shm"; null when it is safe to open. A database
     * whose header can't be read (SQLCipher encrypts it) is taken for a WAL database.
     */
    public static String missing(File db) {
        boolean wal = new File(db.getPath() + "-wal").exists();
        boolean shm = new File(db.getPath() + "-shm").exists();
        if (wal && shm)
            return null;
        // A rollback-journal database needs neither to be read.
        if (!wal && !shm && Boolean.FALSE.equals(isWalMode(db)))
            return null;
        if (wal)
            return "-shm";
        return shm ? "-wal" : "-wal, -shm";
    }

    /** Whether the header says WAL; null when it isn't a plain SQLite header. */
    static Boolean isWalMode(File db) {
        byte[] header = new byte[20];
        try (DataInputStream in = new DataInputStream(new FileInputStream(db))) {
            in.readFully(header);
        } catch (IOException e) {
            return null;
        }
        if (!Arrays.equals(Arrays.copyOf(header, SQLITE_MAGIC.length), SQLITE_MAGIC))
            return null;
        // File format write and read versions: 1 for rollback journal, 2 for WAL.
        return header[18] == 2 || header[19] == 2;
    }
}
