package dev.mooner.kakaodb.rootd;

import java.util.regex.Pattern;

/**
 * The statements the root helper runs: one SELECT, or PRAGMA data_version / table_info, which is all the decoder
 * sends. Nothing that could ATTACH another file or change anything, even though the databases are read-only too.
 *
 * <p>Shared by the plugin and the root helper (this source set is compiled into both), so the plugin's tests can check
 * every statement the decoder makes against it.
 */
public final class ServedSql {

    private ServedSql() {}

    private static final Pattern SELECT = Pattern.compile("(?is)SELECT\\s.*");
    private static final Pattern PRAGMA = Pattern.compile(
        "(?i)PRAGMA\\s+(?:[A-Za-z_][A-Za-z0-9_]*\\s*\\.\\s*)?"
            + "(?:data_version|table_info\\s*\\(\\s*[A-Za-z_][A-Za-z0-9_]*\\s*\\))\\s*;?");

    public static boolean isServed(String sql) {
        String trimmed = sql.trim();
        // One statement: a semicolon may only end it.
        int semicolon = trimmed.indexOf(';');
        if (semicolon >= 0 && semicolon != trimmed.length() - 1)
            return false;
        return SELECT.matcher(trimmed).matches() || PRAGMA.matcher(trimmed).matches();
    }
}
