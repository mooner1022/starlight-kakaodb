/*
 * Opening crypto_user_database is ported from Iris (https://github.com/dolidolih/Iris): the seed lookup
 * (UserDbSaltStore.kt), the passphrase derivation (CryptoUserDatabaseReader.kt) and loading libsqlcipher.so out of the
 * running APK (SqlCipherNative.kt).
 *
 * Copyright (c) 2026 dolidolih. Licensed under the MIT License: see THIRD_PARTY_NOTICES.md.
 */
package dev.mooner.kakaodb.rootd;

import android.database.Cursor;
import android.os.Build;
import android.os.Process;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * KakaoTalk's crypto_user_database: where newer KakaoTalk versions keep the friend list (table {@code user}) and Kakao
 * channels ({@code talk_channel}), instead of KakaoTalk2.db's friends table. The whole file is encrypted with SQLCipher;
 * the values inside are plain.
 *
 * <p>Its passphrase isn't stored anywhere: KakaoTalk derives it from a seed kept in its DataStore preferences
 * ({@value #SEED_KEY} in {@value #SEED_FILE}):
 * <pre>
 *   salt       = UTF-8 of "se" + seed + "ed", zero-padded or cut to 16 bytes
 *   passphrase = PBKDF2WithHmacSHA256(PASSWORD, salt, 4096 iterations, 256 bits)
 * </pre>
 * and hands it to SQLCipher as a passphrase (not a raw key), with SQLCipher 4's defaults.
 *
 * <p>Opened in place and read-only, like KakaoTalk.db, so KakaoTalk's commits show at once.
 */
final class CryptoUserDatabase implements RootDaemon.Database {

    static final String DB_NAME = "crypto_user_database";

    private static final String SEED_KEY = "userDbPassPhraseSalt";
    private static final String SEED_FILE = "Feature_DataStore.pref.preferences_pb";

    /** Where KakaoTalk versions put the DataStore file, relative to KakaoTalk's data directory. */
    private static final String[] SEED_PATHS = {
        "files/datastore/" + SEED_FILE, "files/" + SEED_FILE, "datastore/" + SEED_FILE,
    };

    private static final char[] PASSWORD = { 4, 15, 81, 123, 77, 5, 23, 99, 2, 111, 10, 31, 54, 29, 109, 97 };
    private static final int ITERATIONS = 4096;
    private static final int KEY_BITS = 256;

    private static final String NATIVE_LIB = "libsqlcipher.so";

    private static boolean nativeLoaded;

    /** Installed helpers load SQLCipher from their application's native library directory. */
    static synchronized void loadInstalledNativeLibrary() {
        if (!nativeLoaded) {
            System.loadLibrary("sqlcipher");
            nativeLoaded = true;
        }
    }

    private final SQLiteDatabase db;

    private CryptoUserDatabase(SQLiteDatabase db) {
        this.db = db;
    }

    /** Opens {@code dbDir}/crypto_user_database; KakaoTalk's data directory is {@code dbDir}'s parent. */
    static CryptoUserDatabase open(File dbDir) throws IOException, GeneralSecurityException {
        File file = new File(dbDir, DB_NAME);
        if (!file.isFile())
            throw new FileNotFoundException(file.getPath() + " doesn't exist");
        RootDaemon.requireSafe(file);
        // Not cleared after use: SQLCipher may keep the array to open more connections.
        byte[] passphrase = deriveKey(readSeed(dbDir.getAbsoluteFile().getParentFile()));
        loadNativeLibrary();

        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            file.getPath(), passphrase, null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS, null);
        // A wrong passphrase only shows when the first page is read.
        try (Cursor cursor = db.rawQuery("SELECT count(*) FROM sqlite_master", (String[]) null)) {
            cursor.moveToFirst();
        } catch (RuntimeException e) {
            db.close();
            throw new IOException("the derived passphrase doesn't open " + DB_NAME + ": " + RootDaemon.describe(e), e);
        }
        RootDaemon.info("opened " + file.getPath());
        return new CryptoUserDatabase(db);
    }

    /**
     * Binds the arguments as strings, as Iris does: SQLite compares them as numbers to columns of numeric affinity
     * ({@code user.id}...).
     */
    @Override
    public Cursor query(String sql, Object[] args) {
        String[] strings = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg == null || arg instanceof byte[])
                throw new IllegalArgumentException("Argument " + (i + 1) + " can't be bound on " + DB_NAME);
            strings[i] = arg.toString();
        }
        return db.rawQuery(sql, strings);
    }

    @Override
    public void close() {
        db.close();
    }

    // ------------------------------------------------------------------ passphrase

    static byte[] deriveKey(long seed) throws GeneralSecurityException {
        byte[] salt = Arrays.copyOf(("se" + seed + "ed").getBytes(StandardCharsets.UTF_8), 16);
        PBEKeySpec spec = new PBEKeySpec(PASSWORD, salt, ITERATIONS, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static long readSeed(File appDir) throws IOException {
        for (String path : SEED_PATHS) {
            File file = new File(appDir, path);
            if (!file.isFile())
                continue;
            Long seed = parseSeed(Files.readAllBytes(file.toPath()));
            if (seed != null)
                return seed;
            RootDaemon.info(file.getPath() + " has no " + SEED_KEY);
        }
        throw new FileNotFoundException(
            "no " + SEED_KEY + " in " + appDir + " (looked in " + String.join(", ", SEED_PATHS) + ")");
    }

    /**
     * The int64 of the entry {@value #SEED_KEY} in a DataStore preferences file, null if it isn't there. The file is
     * {@code PreferenceMap { repeated PreferenceEntry entries = 1 }} with {@code PreferenceEntry { string key = 1;
     * Value value = 2 }}, the value holding the long as a varint.
     */
    static Long parseSeed(byte[] bytes) throws IOException {
        ProtoReader map = new ProtoReader(bytes, 0, bytes.length);
        while (map.hasMore()) {
            long tag = map.varint();
            if (tag != (1 << 3 | 2)) {
                map.skip((int) (tag & 7));
                continue;
            }
            ProtoReader entry = map.message();
            String key = null;
            Long value = null;
            while (entry.hasMore()) {
                long entryTag = entry.varint();
                int field = (int) (entryTag >>> 3);
                int wire = (int) (entryTag & 7);
                if (field == 1 && wire == 2)
                    key = entry.string();
                else if (field == 2 && wire == 2)
                    value = firstVarint(entry.message());
                else
                    entry.skip(wire);
            }
            // 0 is a valid seed.
            if (SEED_KEY.equals(key) && value != null)
                return value;
        }
        return null;
    }

    private static Long firstVarint(ProtoReader value) throws IOException {
        while (value.hasMore()) {
            long tag = value.varint();
            if ((tag & 7) == 0)
                return value.varint();
            value.skip((int) (tag & 7));
        }
        return null;
    }

    /** Just enough of the protobuf wire format for a DataStore PreferenceMap. */
    private static final class ProtoReader {

        private final byte[] bytes;
        private final int end;
        private int pos;

        ProtoReader(byte[] bytes, int from, int end) {
            this.bytes = bytes;
            this.pos = from;
            this.end = end;
        }

        boolean hasMore() {
            return pos < end;
        }

        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos >= end)
                    throw malformed();
                int b = bytes[pos++] & 0xff;
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0)
                    return value;
            }
            throw malformed();
        }

        /** A length-delimited field, as its own reader. */
        ProtoReader message() throws IOException {
            long length = varint();
            if (length < 0 || length > end - pos)
                throw malformed();
            ProtoReader inner = new ProtoReader(bytes, pos, pos + (int) length);
            pos += (int) length;
            return inner;
        }

        String string() throws IOException {
            ProtoReader inner = message();
            return new String(bytes, inner.pos, inner.end - inner.pos, StandardCharsets.UTF_8);
        }

        void skip(int wireType) throws IOException {
            switch (wireType) {
                case 0: varint(); break;
                case 1: advance(8); break;
                case 2: message(); break;
                case 5: advance(4); break;
                default: throw malformed();
            }
        }

        private void advance(int count) throws IOException {
            if (count > end - pos)
                throw malformed();
            pos += count;
        }

        private static IOException malformed() {
            return new IOException("malformed DataStore preferences file");
        }
    }

    // ------------------------------------------------------------------ native library

    /**
     * Loads libsqlcipher.so out of this APK: app_process gives this process no native library directory, so
     * System.loadLibrary can't find it. It is extracted next to the APK, in the helper's root-only work directory.
     */
    private static synchronized void loadNativeLibrary() throws IOException {
        if (nativeLoaded)
            return;
        String classPath = System.getProperty("java.class.path", "");
        File apk = new File(classPath.split(File.pathSeparator)[0]);
        if (!apk.isFile())
            throw new FileNotFoundException("can't find the helper's APK (java.class.path=" + classPath + ")");

        String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        try (ZipFile zip = new ZipFile(apk)) {
            for (String abi : abis) {
                ZipEntry entry = zip.getEntry("lib/" + abi + "/" + NATIVE_LIB);
                if (entry == null)
                    continue;
                // The directory is per helper build (see RootHelper's launch script): same size, same file.
                File lib = new File(apk.getParentFile(), "lib/" + abi + "/" + NATIVE_LIB);
                if (!lib.isFile() || lib.length() != entry.getSize())
                    extract(zip, entry, lib);
                System.load(lib.getPath());
                nativeLoaded = true;
                RootDaemon.info("loaded " + lib.getPath());
                return;
            }
            throw new FileNotFoundException(
                NATIVE_LIB + " isn't built for this device's ABIs " + Arrays.toString(abis) + " (the helper has "
                    + abisIn(zip) + "): add one to abiFilters in rootd/build.gradle");
        }
    }

    private static void extract(ZipFile zip, ZipEntry entry, File target) throws IOException {
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("can't create " + dir);
        File tmp = new File(dir, target.getName() + ".tmp");
        try (InputStream in = zip.getInputStream(entry); OutputStream out = new FileOutputStream(tmp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0)
                out.write(buffer, 0, read);
        }
        if (!tmp.renameTo(target))
            throw new IOException("can't write " + target);
    }

    private static TreeSet<String> abisIn(ZipFile zip) {
        TreeSet<String> abis = new TreeSet<>();
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.startsWith("lib/") && name.endsWith("/" + NATIVE_LIB))
                abis.add(name.substring(4, name.length() - NATIVE_LIB.length() - 1));
        }
        return abis;
    }
}
