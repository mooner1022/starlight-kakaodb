/*
 * The seed file paths before KakaoTalk 26.10 come from Iris (https://github.com/dolidolih/Iris), UserDbSaltStore.kt.
 *
 * Copyright (c) 2026 dolidolih. Licensed under the MIT License: see THIRD_PARTY_NOTICES.md.
 */
package dev.mooner.kakaodb.rootd;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where KakaoTalk keeps the files the plugin reads, across KakaoTalk versions.
 *
 * <p>Up to 26.9, the databases are in {@code databases/} and the DataStore preferences in {@code files/datastore/}.
 * KakaoTalk 26.10 moved what belongs to the account into a folder of its own, named the same on both sides:
 * {@code databases/<account>/} (KakaoTalk.db, KakaoTalk2.db, crypto_user_database...) and
 * {@code files/datastore/<account>/} (Features.preferences_pb, which holds crypto_user_database's passphrase seed...).
 * The name (16 hex digits) isn't derived from anything known, so the folder is found by looking for KakaoTalk.db.
 *
 * <p>Plain java.io: the plugin (direct mode), the root helper and the shared UID helper all compile this source set, and
 * each looks with what it can see.
 */
public final class KakaoLayout {

    private KakaoLayout() {}

    public static final String MAIN_DB = "KakaoTalk.db";

    /** The folder in KakaoTalk's data directory that holds the databases, or the account folders that hold them. */
    public static final String DATABASES = "databases";

    /** Up to 26.9, relative to KakaoTalk's data directory. */
    private static final String LEGACY_SEED_FILE = "Feature_DataStore.pref.preferences_pb";

    private static final String[] LEGACY_SEED_PATHS = {
        "files/datastore/" + LEGACY_SEED_FILE, "files/" + LEGACY_SEED_FILE, "datastore/" + LEGACY_SEED_FILE,
    };

    /** 26.10, in the account's DataStore folder. */
    private static final String ACCOUNT_SEED_FILE = "Features.preferences_pb";

    private static final String DATASTORE = "files/datastore";
    private static final String PREFERENCES_SUFFIX = ".preferences_pb";

    /** DataStore files past this size aren't looked into when searching for the seed: they hold more than settings. */
    public static final long MAX_SCANNED_SEED_FILE = 1024 * 1024;

    /**
     * The folders directly in {@code dir} that hold KakaoTalk.db (KakaoTalk 26.10's account folders), the most recently
     * written first. Empty when there are none, or when {@code dir} can't be listed.
     */
    public static List<File> accountDirs(File dir) {
        File[] children = dir.listFiles();
        List<File> found = new ArrayList<>();
        if (children == null)
            return found;
        // Read once: KakaoTalk may write while this sorts.
        Map<File, Long> written = new HashMap<>();
        for (File child : children) {
            if (child.isDirectory() && new File(child, MAIN_DB).isFile()) {
                found.add(child);
                written.put(child, lastWrite(child));
            }
        }
        // Latest first; the name breaks ties, so the order never depends on the listing's.
        found.sort(Comparator.comparing((File child) -> written.get(child)).reversed().thenComparing(File::getName));
        return found;
    }

    /**
     * When KakaoTalk last committed to the databases in {@code dbDir}: commits go to KakaoTalk.db-wal, KakaoTalk.db
     * only changes on checkpoints. 0 when neither is there.
     */
    static long lastWrite(File dbDir) {
        return Math.max(new File(dbDir, MAIN_DB).lastModified(), new File(dbDir, MAIN_DB + "-wal").lastModified());
    }

    /** The account folder {@code dbDir} is ({@code databases/<account>}), or null when it is something else. */
    public static String accountOf(File dbDir) {
        File parent = dbDir.getAbsoluteFile().getParentFile();
        return parent != null && DATABASES.equals(parent.getName()) ? dbDir.getName() : null;
    }

    /**
     * KakaoTalk's data directory, for its databases in {@code dbDir}: the folder that holds {@code databases}, whether
     * {@code dbDir} is that or an account folder in it. For a folder laid out otherwise (a copy...), its parent.
     */
    public static File appDataDir(File dbDir) {
        File dir = dbDir.getAbsoluteFile();
        File parent = dir.getParentFile();
        if (accountOf(dir) != null && parent.getParentFile() != null)
            return parent.getParentFile();
        return parent;
    }

    /**
     * Where crypto_user_database's passphrase seed may be, for the databases in {@code dbDir}, in the order to look:
     * the account's Features.preferences_pb (26.10), the files earlier versions used, then the other DataStore files of
     * the account and the top-level ones. Only files that exist. Never another account's folder: its seed would be the
     * wrong one.
     */
    public static List<File> seedFiles(File dbDir) {
        File appDir = appDataDir(dbDir);
        String account = accountOf(dbDir);
        Set<File> files = new LinkedHashSet<>();
        if (account != null)
            files.add(new File(appDir, DATASTORE + "/" + account + "/" + ACCOUNT_SEED_FILE));
        for (String path : LEGACY_SEED_PATHS)
            files.add(new File(appDir, path));
        if (account != null)
            files.addAll(preferenceFiles(new File(appDir, DATASTORE + "/" + account)));
        files.addAll(preferenceFiles(new File(appDir, DATASTORE)));

        List<File> existing = new ArrayList<>();
        for (File file : files)
            if (file.isFile())
                existing.add(file);
        return existing;
    }

    /** What {@link #seedFiles} looks for first, relative to KakaoTalk's data directory: for error messages. */
    public static List<String> knownSeedPaths(File dbDir) {
        List<String> paths = new ArrayList<>();
        String account = accountOf(dbDir);
        if (account != null)
            paths.add(DATASTORE + "/" + account + "/" + ACCOUNT_SEED_FILE);
        paths.addAll(Arrays.asList(LEGACY_SEED_PATHS));
        return paths;
    }

    /** The DataStore preferences files directly in {@code dir}, by name; not too large to read. */
    private static List<File> preferenceFiles(File dir) {
        File[] children = dir.listFiles();
        List<File> found = new ArrayList<>();
        if (children == null)
            return found;
        for (File child : children)
            if (child.getName().endsWith(PREFERENCES_SUFFIX) && child.isFile() && child.length() <= MAX_SCANNED_SEED_FILE)
                found.add(child);
        found.sort(Comparator.comparing(File::getName));
        return found;
    }
}
