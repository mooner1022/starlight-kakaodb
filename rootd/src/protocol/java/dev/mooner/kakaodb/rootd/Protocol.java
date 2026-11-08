package dev.mooner.kakaodb.rootd;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The wire format between the plugin and {@link RootDaemon}. Both sides are compiled from this class, so they can't
 * drift apart; the plugin also checks the daemon's build at the handshake.
 *
 * <p>Every request is an op byte followed by its arguments. Replies start with {@link #STATUS_OK} or
 * {@link #STATUS_ERROR} followed by a message. A connection is either a session (OPEN and ATTACH, or OPEN_USER_DB,
 * then QUERY... until it is closed, the database closing with it) or a watch (WATCH, then {@link #EVENT_CHANGED} bytes
 * until it is closed).
 */
public final class Protocol {

    private Protocol() {}

    /**
     * Bumped on any change to the format below, or to the shared helper's binder reply.
     * 4: the shared helper's reply carries its build ({@link #SHARED_DESCRIPTOR}).
     */
    public static final int VERSION = 4;

    // ------------------------------------------------------------------ shared UID mode
    // :sharedd, installed in KakaoTalk's shared UID, serves this same protocol over a socket pair it hands out through
    // its bound service. One transaction, FIRST_CALL_TRANSACTION:
    //   interface token SHARED_DESCRIPTOR, int VERSION
    //   -> no exception, String helper build, ParcelFileDescriptor (the socket)
    // A helper speaking another VERSION throws IllegalArgumentException with SHARED_MISMATCH in its message.

    public static final String KAKAO_PACKAGE = "com.kakao.talk";
    public static final String STARLIGHT_PACKAGE = "dev.mooner.starlight";
    public static final String SHARED_PACKAGE = "dev.mooner.kakaodb.sharedd";
    public static final String SHARED_SERVICE = SHARED_PACKAGE + ".SharedService";
    public static final String SHARED_DESCRIPTOR = SHARED_PACKAGE + ".ISharedService";
    /** In the message a helper of another VERSION refuses with (helpers before 4 said only this). */
    public static final String SHARED_MISMATCH = "Protocol version mismatch";

    public static final String ENV_TOKEN = "KAKAODB_ROOTD_TOKEN";
    public static final String ENV_CLIENT_UID = "KAKAODB_ROOTD_UID";
    public static final String ENV_BUILD = "KAKAODB_ROOTD_BUILD";

    /** Printed by the daemon once it listens, followed by its socket name and pid; the launch script waits for it. */
    public static final String READY = "KAKAODB_ROOTD_READY";

    /** token, version -> OK, build */
    public static final int OP_HELLO = 1;
    /*
     * The helper runs as root, so it only serves what the plugin needs: files named like KakaoTalk's databases
     * (KakaoTalk.db, KakaoTalk2.db, crypto_user_database, and their -wal for watches), and SELECT or
     * PRAGMA data_version/table_info statements.
     */

    /** dir, names -> OK, dir exists, one FILE_* mask per name */
    public static final int OP_CHECK = 2;
    /** path -> OK */
    public static final int OP_OPEN = 3;
    /** schema name, path -> OK */
    public static final int OP_ATTACH = 4;
    /** sql (one SELECT, or PRAGMA data_version/table_info), max rows (0: all), args -> OK, columns, then ROW... END (or ROW_ERROR, message) */
    public static final int OP_QUERY = 5;
    /** dir, names -> OK, then EVENT_CHANGED whenever one of the files changes */
    public static final int OP_WATCH = 6;
    /** database dir -> OK; opens crypto_user_database there (SQLCipher) as this session's database */
    public static final int OP_OPEN_USER_DB = 7;

    public static final int STATUS_OK = 0;
    public static final int STATUS_ERROR = 1;

    public static final int END = 0;
    public static final int ROW = 1;
    public static final int ROW_ERROR = 2;

    public static final int EVENT_CHANGED = 1;

    public static final int FILE_EXISTS = 1;
    public static final int FILE_WAL = 2;
    public static final int FILE_SHM = 4;
    /** Opening it would create its -wal or -shm (see {@link WalFiles}): the helper refuses to. */
    public static final int FILE_UNSAFE = 8;

    private static final int T_NULL = 0;
    private static final int T_LONG = 1;
    private static final int T_DOUBLE = 2;
    private static final int T_STRING = 3;
    private static final int T_BLOB = 4;

    /** Upper bound for one string or blob; anything larger means the stream is out of sync. */
    private static final int MAX_LENGTH = 64 * 1024 * 1024;

    /** Abstract socket name, per client uid so several users (or profiles) don't collide. */
    public static String socketName(int clientUid) {
        return "dev.mooner.kakaodb.rootd." + clientUid;
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    public static String readString(DataInputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    /** Writes a bind argument or a column value, with the same typing as AndroidSqliteSession's binding. */
    public static void writeValue(DataOutputStream out, Object value) throws IOException {
        if (value == null) {
            out.writeByte(T_NULL);
        } else if (value instanceof Boolean) {
            out.writeByte(T_LONG);
            out.writeLong((Boolean) value ? 1L : 0L);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            out.writeByte(T_LONG);
            out.writeLong(((Number) value).longValue());
        } else if (value instanceof Double || value instanceof Float) {
            out.writeByte(T_DOUBLE);
            out.writeDouble(((Number) value).doubleValue());
        } else if (value instanceof byte[]) {
            out.writeByte(T_BLOB);
            writeBytes(out, (byte[]) value);
        } else {
            out.writeByte(T_STRING);
            writeString(out, value.toString());
        }
    }

    /** Reads a value written by {@link #writeValue}: null, Long, Double, String or byte[]. */
    public static Object readValue(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        switch (type) {
            case T_NULL:   return null;
            case T_LONG:   return in.readLong();
            case T_DOUBLE: return in.readDouble();
            case T_STRING: return readString(in);
            case T_BLOB:   return readBytes(in);
            default:       throw new IOException("Unknown value type: " + type);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_LENGTH)
            throw new IOException("Bad length: " + length);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
