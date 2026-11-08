package dev.mooner.kakaodb.rootd;

import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQuery;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.FileObserver;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Root helper the plugin starts with su:
 * {@code CLASSPATH=rootd.apk app_process / --nice-name=kakaodb-rootd dev.mooner.kakaodb.rootd.RootDaemon}
 *
 * <p>It runs as uid 0 in the root manager's SELinux domain, so it can open KakaoTalk's database files where they are,
 * which the plugin's own process can't: on Android 10+ SELinux MLS categories block reading another app's data,
 * whatever the file modes say. It opens them read-only and serves queries and file-change events over an abstract
 * UNIX socket, only to the uid that started it and only after a token handshake (other plugins share that uid).
 * It exits once no client has been connected for {@link #IDLE_EXIT_MILLIS}.
 *
 * <p>A session reads either KakaoTalk.db (with KakaoTalk2.db attached) through the framework SQLite, or
 * crypto_user_database through SQLCipher ({@link CryptoUserDatabase}), where newer KakaoTalk versions keep friends.
 *
 * <p>This process only has the framework and what this APK brings (no StarLight), so this stays plain Java.
 */
public final class RootDaemon {

    private static final String TAG = "kakaodb-rootd";

    private static final long IDLE_EXIT_MILLIS = 30_000L;

    private static final int FILE_EVENTS =
        FileObserver.MODIFY | FileObserver.CLOSE_WRITE | FileObserver.CREATE | FileObserver.MOVED_TO;

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * The only files this root process opens, checks or watches, symbolic links resolved: KakaoTalk's databases. Any
     * StarLight plugin runs as the client uid, so the helper must not read anything else on their behalf.
     */
    private static final Set<String> DATABASE_NAMES =
        new HashSet<>(Arrays.asList("KakaoTalk.db", "KakaoTalk2.db", CryptoUserDatabase.DB_NAME));

    /** A client must say hello within this; one that never does would otherwise keep the helper from exiting. */
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 5_000;

    private static final int MAX_NAMES = 64;
    private static final int MAX_ARGS = 10_000;

    private static final String[] NO_ARGS = new String[0];

    private static final AtomicInteger clients = new AtomicInteger();
    private static volatile long idleSince = System.currentTimeMillis();

    private static byte[] token;
    private static int clientUid;
    private static String build;

    private RootDaemon() {}

    public static void main(String[] args) {
        String tokenEnv = System.getenv(Protocol.ENV_TOKEN);
        String uidEnv = System.getenv(Protocol.ENV_CLIENT_UID);
        String buildEnv = System.getenv(Protocol.ENV_BUILD);
        if (tokenEnv == null || tokenEnv.isEmpty() || uidEnv == null) {
            error("missing " + Protocol.ENV_TOKEN + " or " + Protocol.ENV_CLIENT_UID, null);
            System.exit(2);
            return;
        }
        try {
            clientUid = Integer.parseInt(uidEnv.trim());
        } catch (NumberFormatException e) {
            error("bad " + Protocol.ENV_CLIENT_UID + ": " + uidEnv, null);
            System.exit(2);
            return;
        }
        token = tokenEnv.getBytes(StandardCharsets.UTF_8);
        build = buildEnv != null ? buildEnv : "";

        String name = Protocol.socketName(clientUid);
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(name);
        } catch (IOException e) {
            error("can't listen on " + name, e);
            System.exit(3);
            return;
        }
        startIdleWatchdog();
        // The launch script takes the pid from here: the last word.
        System.out.println(Protocol.READY + " " + name + " " + android.os.Process.myPid());
        System.out.flush();
        info("listening on " + name + " for uid " + clientUid + ", build " + build);

        while (true) {
            final LocalSocket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                error("accept failed", e);
                System.exit(4);
                return;
            }
            clients.incrementAndGet();
            Thread thread = new Thread(() -> serve(socket), "rootd-client");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void startIdleWatchdog() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5_000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (clients.get() == 0 && System.currentTimeMillis() - idleSince > IDLE_EXIT_MILLIS) {
                    info("no clients for " + IDLE_EXIT_MILLIS / 1000 + "s, exiting");
                    System.exit(0);
                }
            }
        }, "rootd-idle");
        thread.setDaemon(true);
        thread.start();
    }

    private static void serve(LocalSocket socket) {
        try {
            Credentials peer = socket.getPeerCredentials();
            if (peer.getUid() != clientUid) {
                error("refused a connection from uid " + peer.getUid(), null);
                return;
            }
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            if (handshake(in, out)) {
                // Sessions sit idle between queries, and watches wait for file changes.
                socket.setSoTimeout(0);
                serveAuthenticated(in, out);
            } else {
                error("refused a connection: failed handshake", null);
            }
        } catch (IOException e) {
            // The client went away.
        } catch (RuntimeException e) {
            error("connection failed", e);
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            // idleSince first: the watchdog only looks at it once the count is 0.
            idleSince = System.currentTimeMillis();
            clients.decrementAndGet();
        }
    }

    /**
     * Logs to logcat (tag {@value #TAG}) and to stdout, which the launch script sends to rootd-&lt;uid&gt;.log in the
     * work directory: logcat may already be gone by the time someone looks into a failed start.
     */
    static void info(String message) {
        System.out.println("rootd: " + message);
        Log.i(TAG, message);
    }

    static void error(String message, Throwable e) {
        System.err.println("rootd: " + message + (e != null ? ": " + e : ""));
        if (e != null)
            e.printStackTrace();
        Log.e(TAG, message, e);
    }

    private static boolean handshake(DataInputStream in, DataOutputStream out) throws IOException {
        if (in.readUnsignedByte() != Protocol.OP_HELLO)
            return false;
        byte[] given = Protocol.readString(in).getBytes(StandardCharsets.UTF_8);
        int version = in.readInt();
        if (!MessageDigest.isEqual(token, given)) {
            reject(out, "bad token");
            return false;
        }
        if (version != Protocol.VERSION) {
            reject(out, "protocol " + version + " requested, this daemon speaks " + Protocol.VERSION);
            return false;
        }
        out.writeByte(Protocol.STATUS_OK);
        Protocol.writeString(out, build);
        out.flush();
        return true;
    }

    /** Runs the database protocol on a transport whose caller has already been authenticated. */
    public static void serveAuthenticated(DataInputStream in, DataOutputStream out) throws IOException {
        new Connection(in, out, null).run();
    }

    /** Restricts an installed helper to one canonical database directory. */
    public static void serveAuthenticated(DataInputStream in, DataOutputStream out, File directory) throws IOException {
        new Connection(in, out, directory.getCanonicalFile()).run();
    }

    /** Initializes SQLCipher in an installed helper before accepting connections. */
    public static void loadInstalledNativeLibrary() {
        CryptoUserDatabase.loadInstalledNativeLibrary();
    }

    private static void reject(DataOutputStream out, String message) throws IOException {
        out.writeByte(Protocol.STATUS_ERROR);
        Protocol.writeString(out, message);
        out.flush();
    }

    static String describe(Throwable e) {
        String message = e.getMessage();
        return message != null ? message : e.toString();
    }

    /**
     * Refuses a database whose -wal or -shm is missing: SQLite would create them, even read-only, as this process and
     * with its SELinux label rather than KakaoTalk's, and KakaoTalk might then be unable to open its own database
     * (see {@link WalFiles}). KakaoTalk recreates both when it opens the database.
     */
    static void requireSafe(File db) {
        String missing = WalFiles.missing(db);
        if (missing != null)
            throw new IllegalStateException(
                db.getPath() + " has no " + missing + ", which opening it would create: open KakaoTalk once, then reconnect");
    }

    /** [path], symbolic links resolved, if it is one of KakaoTalk's databases. Throws a SecurityException if not. */
    static File allowedDatabase(String path) {
        File canonical;
        try {
            canonical = new File(path).getCanonicalFile();
        } catch (IOException e) {
            throw new IllegalArgumentException("Bad path: " + path, e);
        }
        if (!DATABASE_NAMES.contains(canonical.getName()))
            throw new SecurityException("Not a KakaoTalk database: " + path);
        return canonical;
    }

    /** Requires each name to be one of KakaoTalk's databases, or (with [walToo]) the -wal of one. */
    private static void requireDatabaseNames(String[] names, boolean walToo) {
        for (String name : names) {
            String base = walToo && name.endsWith("-wal") ? name.substring(0, name.length() - "-wal".length()) : name;
            if (!DATABASE_NAMES.contains(base))
                throw new SecurityException("Not a KakaoTalk database: " + name);
        }
    }

    /** Requires one statement the decoder would send (see {@link ServedSql}). */
    private static void requireServedSql(String sql) {
        if (!ServedSql.isServed(sql))
            throw new SecurityException("Only single SELECT and PRAGMA data_version/table_info statements are served");
    }

    /** What a session queries. */
    interface Database {

        /** Runs [sql]; [args] are what {@link Protocol#readValue} reads (null, Long, Double, String, byte[]). */
        Cursor query(String sql, Object[] args);

        void close();
    }

    /** KakaoTalk.db on the framework SQLite, read-only; KakaoTalk2.db gets attached to it. */
    private static final class FrameworkDatabase implements Database {

        private final SQLiteDatabase db;

        FrameworkDatabase(File path) {
            requireSafe(path);
            db = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
        }

        void attach(String name, File path) {
            if (!IDENTIFIER.matcher(name).matches())
                throw new IllegalArgumentException("Illegal schema name: " + name);
            requireSafe(path);
            // Attached databases get the main connection's flags: read-only too.
            db.execSQL("ATTACH DATABASE ? AS " + name, new Object[] { path.getPath() });
        }

        @Override
        public Cursor query(String sql, Object[] args) {
            // Binds each argument with its own type: rawQuery would bind them all as strings.
            return db.rawQueryWithFactory((database, driver, editTable, statement) -> {
                for (int i = 0; i < args.length; i++)
                    bind(statement, i + 1, args[i]);
                return new SQLiteCursor(driver, editTable, statement);
            }, sql, NO_ARGS, "");
        }

        @Override
        public void close() {
            db.close();
        }

        private static void bind(SQLiteQuery statement, int index, Object arg) {
            if (arg == null)
                statement.bindNull(index);
            else if (arg instanceof Long)
                statement.bindLong(index, (Long) arg);
            else if (arg instanceof Double)
                statement.bindDouble(index, (Double) arg);
            else if (arg instanceof byte[])
                statement.bindBlob(index, (byte[]) arg);
            else
                statement.bindString(index, arg.toString());
        }
    }

    /**
     * One FileObserver per directory, shared by every watch on it. FileObserver keeps a single observer per inotify
     * watch, and the kernel gives a process the same watch for the same directory: a second FileObserver on it would
     * take the first one's events, and stopping either would stop both. That happens when the plugin reconnects and
     * the new watch starts before this process has seen the old one end.
     */
    private static final class DirectoryWatcher {

        interface Listener {
            void onChanged(String name);
        }

        private static final Map<String, DirectoryWatcher> watchers = new HashMap<>();

        private final FileObserver observer;
        private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

        @SuppressWarnings("deprecation") // FileObserver(File, int) needs API 29.
        private DirectoryWatcher(String dir) {
            observer = new FileObserver(dir, FILE_EVENTS) {
                @Override
                public void onEvent(int event, String path) {
                    if (path != null)
                        for (Listener listener : listeners)
                            listener.onChanged(path);
                }
            };
        }

        static synchronized void add(String dir, Listener listener) {
            DirectoryWatcher watcher = watchers.get(dir);
            if (watcher == null) {
                watcher = new DirectoryWatcher(dir);
                watchers.put(dir, watcher);
                watcher.listeners.add(listener);
                watcher.observer.startWatching();
            } else {
                watcher.listeners.add(listener);
            }
        }

        static synchronized void remove(String dir, Listener listener) {
            DirectoryWatcher watcher = watchers.get(dir);
            if (watcher == null || !watcher.listeners.remove(listener))
                return;
            if (watcher.listeners.isEmpty()) {
                watcher.observer.stopWatching();
                watchers.remove(dir);
            }
        }
    }

    /** One client connection: a session on a database, or a watch. */
    private static final class Connection {

        private final DataInputStream in;
        private final DataOutputStream out;
        private final File allowedDir;
        private Database db;

        Connection(DataInputStream in, DataOutputStream out, File allowedDir) {
            this.in = in;
            this.out = out;
            this.allowedDir = allowedDir;
        }

        private File checkedDir(File dir) throws IOException {
            File canonical = dir.getCanonicalFile();
            if (allowedDir != null && !allowedDir.equals(canonical))
                throw new SecurityException("Database directory is not permitted: " + dir);
            return canonical;
        }

        private File checkedDatabase(String path) throws IOException {
            File file = allowedDatabase(path);
            checkedDir(file.getParentFile());
            return file;
        }

        void run() throws IOException {
            try {
                while (true) {
                    int op;
                    try {
                        op = in.readUnsignedByte();
                    } catch (EOFException e) {
                        return;
                    }
                    switch (op) {
                        case Protocol.OP_CHECK:
                            check();
                            break;
                        case Protocol.OP_OPEN:
                            open(Protocol.readString(in));
                            break;
                        case Protocol.OP_OPEN_USER_DB:
                            openUserDatabase(Protocol.readString(in));
                            break;
                        case Protocol.OP_ATTACH:
                            attach(Protocol.readString(in), Protocol.readString(in));
                            break;
                        case Protocol.OP_QUERY:
                            query();
                            break;
                        case Protocol.OP_WATCH:
                            watch();
                            return;
                        default:
                            throw new IOException("Unknown op: " + op);
                    }
                    out.flush();
                }
            } finally {
                if (db != null)
                    db.close();
            }
        }

        private void check() throws IOException {
            File dir = new File(Protocol.readString(in));
            String[] names = readNames();
            try {
                checkedDir(dir);
                requireDatabaseNames(names, false);
                if (allowedDir != null) {
                    try {
                        if (!OsConstants.S_ISDIR(Os.stat(dir.getPath()).st_mode))
                            throw new IllegalStateException("Database path is not a directory: " + dir);
                    } catch (ErrnoException e) {
                        if (e.errno == OsConstants.ENOENT)
                            throw new IllegalStateException("Database directory is not visible: " + dir
                                + ". Open KakaoTalk and sign in to create its databases, then reconnect."
                                + " If it still fails, the device may be isolating app data.", e);
                        if (e.errno == OsConstants.EACCES || e.errno == OsConstants.EPERM)
                            throw new IllegalStateException("Permission denied accessing database directory: " + dir
                                + ". The device may be isolating app data even for a shared UID.", e);
                        throw new IllegalStateException("Cannot inspect database directory: " + dir + " (errno " + e.errno + ")", e);
                    }
                }
            } catch (RuntimeException | IOException e) {
                fail(e);
                return;
            }
            out.writeByte(Protocol.STATUS_OK);
            out.writeBoolean(dir.isDirectory());
            for (String name : names) {
                File file = new File(dir, name);
                int mask = 0;
                if (file.isFile()) {
                    mask |= Protocol.FILE_EXISTS;
                    if (WalFiles.missing(file) != null)
                        mask |= Protocol.FILE_UNSAFE;
                }
                if (new File(dir, name + "-wal").exists())
                    mask |= Protocol.FILE_WAL;
                if (new File(dir, name + "-shm").exists())
                    mask |= Protocol.FILE_SHM;
                out.writeByte(mask);
            }
        }

        private void open(String path) throws IOException {
            try {
                if (db != null)
                    throw new IllegalStateException("A database is already open on this connection");
                db = new FrameworkDatabase(checkedDatabase(path));
                out.writeByte(Protocol.STATUS_OK);
            } catch (RuntimeException | IOException e) {
                fail(e);
            }
        }

        private void openUserDatabase(String dbDir) throws IOException {
            try {
                if (db != null)
                    throw new IllegalStateException("A database is already open on this connection");
                File file = checkedDatabase(new File(dbDir, CryptoUserDatabase.DB_NAME).getPath());
                if (!file.getName().equals(CryptoUserDatabase.DB_NAME))
                    throw new SecurityException("Not " + CryptoUserDatabase.DB_NAME + ": " + file);
                db = CryptoUserDatabase.open(file.getParentFile());
                out.writeByte(Protocol.STATUS_OK);
            } catch (RuntimeException | IOException | GeneralSecurityException | LinkageError e) {
                // LinkageError: libsqlcipher.so failed to load. The rest of the helper keeps working.
                error("couldn't open " + CryptoUserDatabase.DB_NAME + " in " + dbDir, e);
                fail(e);
            }
        }

        private void attach(String name, String path) throws IOException {
            try {
                if (!(db instanceof FrameworkDatabase))
                    throw new IllegalStateException("No KakaoTalk.db is open on this connection");
                ((FrameworkDatabase) db).attach(name, checkedDatabase(path));
                out.writeByte(Protocol.STATUS_OK);
            } catch (RuntimeException | IOException e) {
                fail(e);
            }
        }

        private void query() throws IOException {
            String sql = Protocol.readString(in);
            int maxRows = in.readInt();
            int argc = in.readInt();
            if (argc < 0 || argc > MAX_ARGS)
                throw new IOException("Bad argument count: " + argc);
            final Object[] args = new Object[argc];
            for (int i = 0; i < argc; i++)
                args[i] = Protocol.readValue(in);

            final Cursor cursor;
            try {
                if (db == null)
                    throw new IllegalStateException("No database is open on this connection");
                requireServedSql(sql);
                cursor = db.query(sql, args);
            } catch (RuntimeException e) {
                fail(e);
                return;
            }

            try {
                String[] columns = cursor.getColumnNames();
                out.writeByte(Protocol.STATUS_OK);
                out.writeInt(columns.length);
                for (String column : columns)
                    Protocol.writeString(out, column);
                // The statement only runs on the first move, so errors can still come up after the header.
                try {
                    int rows = 0;
                    while ((maxRows <= 0 || rows < maxRows) && cursor.moveToNext()) {
                        out.writeByte(Protocol.ROW);
                        for (int i = 0; i < columns.length; i++)
                            Protocol.writeValue(out, value(cursor, i));
                        rows++;
                    }
                    out.writeByte(Protocol.END);
                } catch (RuntimeException e) {
                    out.writeByte(Protocol.ROW_ERROR);
                    Protocol.writeString(out, describe(e));
                }
            } finally {
                cursor.close();
            }
        }

        /** Sends {@link Protocol#EVENT_CHANGED} whenever one of the files changes, until the client hangs up. */
        private void watch() throws IOException {
            String dirArg = Protocol.readString(in);
            String[] requested = readNames();
            final String dir;
            try {
                requireDatabaseNames(requested, true);
                dir = checkedDir(new File(dirArg)).getPath();
            } catch (RuntimeException | IOException e) {
                fail(e);
                return;
            }
            final Set<String> names = new HashSet<>(Arrays.asList(requested));
            final Object lock = new Object();
            final boolean[] pending = { false };

            DirectoryWatcher.Listener listener = name -> {
                if (names.contains(name)) {
                    synchronized (lock) {
                        pending[0] = true;
                        lock.notifyAll();
                    }
                }
            };
            out.writeByte(Protocol.STATUS_OK);
            out.flush();
            DirectoryWatcher.add(dir, listener);

            // Coalesces bursts of inotify events into one byte each time the writer gets to run.
            Thread writer = new Thread(() -> {
                try {
                    while (true) {
                        synchronized (lock) {
                            while (!pending[0])
                                lock.wait();
                            pending[0] = false;
                        }
                        out.writeByte(Protocol.EVENT_CHANGED);
                        out.flush();
                    }
                } catch (InterruptedException | IOException e) {
                    // Stopped, or the client went away.
                }
            }, "rootd-watch");
            writer.setDaemon(true);
            writer.start();

            try {
                // The client sends nothing more: this returns when it hangs up.
                while (in.read() != -1) {
                    // ignore
                }
            } finally {
                DirectoryWatcher.remove(dir, listener);
                writer.interrupt();
            }
        }

        private String[] readNames() throws IOException {
            int count = in.readInt();
            if (count < 0 || count > MAX_NAMES)
                throw new IOException("Bad name count: " + count);
            String[] names = new String[count];
            for (int i = 0; i < count; i++)
                names[i] = Protocol.readString(in);
            return names;
        }

        private void fail(Throwable e) throws IOException {
            out.writeByte(Protocol.STATUS_ERROR);
            Protocol.writeString(out, describe(e));
        }

        private static Object value(Cursor cursor, int index) {
            switch (cursor.getType(index)) {
                case Cursor.FIELD_TYPE_INTEGER: return cursor.getLong(index);
                case Cursor.FIELD_TYPE_FLOAT:   return cursor.getDouble(index);
                case Cursor.FIELD_TYPE_STRING:  return cursor.getString(index);
                case Cursor.FIELD_TYPE_BLOB:    return cursor.getBlob(index);
                default:                        return null;
            }
        }
    }
}
