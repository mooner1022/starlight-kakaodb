package dev.mooner.kakaodb.sharedd;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.text.format.DateUtils;

import dev.mooner.kakaodb.rootd.Protocol;
import dev.mooner.kakaodb.rootd.WalFiles;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What the status screen shows: whether this helper can serve StarLight, item by item. */
final class HelperChecks {

    private HelperChecks() {}

    private static final String MAIN_DB = "KakaoTalk.db";
    private static final String SUB_DB = "KakaoTalk2.db";
    private static final String USER_DB = "crypto_user_database";

    /** In order of severity. */
    enum State { OK, INFO, WARN, ERROR }

    static final class Check {
        final State state;
        final String title;
        final String detail;

        Check(State state, String title, String detail) {
            this.state = state;
            this.title = title;
            this.detail = detail;
        }
    }

    static final class Report {
        /** Whether the setup works; ERROR when the helper can't serve. */
        final State overall;
        final String headline;
        final String summary;
        final List<Check> setup;
        final List<Check> connection;

        Report(State overall, String headline, String summary, List<Check> setup, List<Check> connection) {
            this.overall = overall;
            this.headline = headline;
            this.summary = summary;
            this.setup = setup;
            this.connection = connection;
        }
    }

    /** KakaoTalk's database directory, which the service limits sessions to. */
    static File databaseDir(Context context) throws PackageManager.NameNotFoundException {
        return new File(context.getPackageManager().getApplicationInfo(Protocol.KAKAO_PACKAGE, 0).dataDir, "databases");
    }

    static Report run(Context context) {
        List<Check> setup = new ArrayList<>();
        PackageManager packages = context.getPackageManager();

        setup.add(patch(context, packages));
        PackageInfo kakao = packageInfo(packages, Protocol.KAKAO_PACKAGE);
        boolean sameUid = kakao != null && kakao.applicationInfo.uid == Process.myUid();
        setup.add(kakaoTalk(context, kakao, sameUid));
        setup.add(starLight(context, packageInfo(packages, Protocol.STARLIGHT_PACKAGE)));
        if (sameUid) {
            File dir = new File(kakao.applicationInfo.dataDir, "databases");
            setup.add(databases(context, dir));
            setup.add(friendList(context, dir));
        }

        List<Check> connection = connection(context);

        State overall = State.OK;
        for (Check check : setup)
            if (check.state.compareTo(overall) > 0)
                overall = check.state;
        String headline;
        String summary;
        if (overall == State.ERROR) {
            headline = context.getString(R.string.summary_error);
            summary = context.getString(R.string.summary_error_detail);
        } else if (overall == State.WARN) {
            headline = context.getString(R.string.summary_warn);
            summary = context.getString(R.string.summary_warn_detail);
        } else if (HelperState.sessions.get() > 0) {
            headline = context.getString(R.string.summary_connected);
            summary = context.getString(R.string.summary_connected_detail);
        } else {
            headline = context.getString(R.string.summary_ready);
            summary = context.getString(R.string.summary_ready_detail);
            overall = State.INFO;
        }
        return new Report(overall, headline, summary, setup, connection);
    }

    @SuppressWarnings("deprecation") // sharedUserId: what the setup tool patches in, deprecated or not.
    private static Check patch(Context context, PackageManager packages) {
        String title = context.getString(R.string.check_patch);
        PackageInfo self = packageInfo(packages, context.getPackageName());
        String sharedUserId = self != null ? self.sharedUserId : null;
        if (sharedUserId == null)
            return new Check(State.ERROR, title, context.getString(R.string.check_patch_missing));
        return new Check(State.OK, title, sharedUserId);
    }

    private static Check kakaoTalk(Context context, PackageInfo kakao, boolean sameUid) {
        String title = context.getString(R.string.check_kakao);
        if (kakao == null)
            return new Check(State.ERROR, title, context.getString(R.string.check_kakao_missing));
        if (!sameUid)
            return new Check(State.ERROR, title,
                context.getString(R.string.check_kakao_uid_differs, kakao.applicationInfo.uid, Process.myUid()));
        return new Check(State.OK, title,
            context.getString(R.string.check_kakao_ok, String.valueOf(kakao.versionName), kakao.applicationInfo.uid));
    }

    private static Check starLight(Context context, PackageInfo starLight) {
        String title = context.getString(R.string.check_starlight);
        if (starLight == null)
            return new Check(State.ERROR, title, context.getString(R.string.check_starlight_missing));
        return new Check(State.OK, title, context.getString(R.string.check_version, String.valueOf(starLight.versionName)));
    }

    private static Check databases(Context context, File dir) {
        String title = context.getString(R.string.check_databases);
        if (!dir.isDirectory())
            return new Check(State.ERROR, title, context.getString(R.string.check_databases_no_dir));
        for (String name : new String[] { MAIN_DB, SUB_DB }) {
            File db = new File(dir, name);
            if (!db.isFile())
                return new Check(State.ERROR, title, context.getString(R.string.check_databases_missing, name));
            String missing = WalFiles.missing(db);
            if (missing != null)
                return new Check(State.WARN, title, context.getString(R.string.check_databases_unsafe, name, missing));
        }
        return new Check(State.OK, title, MAIN_DB + ", " + SUB_DB);
    }

    private static Check friendList(Context context, File dir) {
        String title = context.getString(R.string.check_friends);
        if (!new File(dir, USER_DB).isFile())
            return new Check(State.INFO, title, context.getString(R.string.check_friends_absent));
        String error = HelperState.loadSqlCipher();
        if (error != null)
            return new Check(State.ERROR, title, context.getString(R.string.check_friends_no_sqlcipher, error));
        return new Check(State.OK, title, context.getString(R.string.check_friends_ok));
    }

    private static List<Check> connection(Context context) {
        String title = context.getString(R.string.check_connection);
        int sessions = HelperState.sessions.get();
        long last = HelperState.lastConnectedAt;
        Check state;
        if (sessions > 0)
            state = new Check(State.OK, title, context.getString(R.string.check_connection_active, sessions, time(context, last)));
        else if (last > 0)
            state = new Check(State.INFO, title, context.getString(R.string.check_connection_idle, time(context, last)));
        else
            state = new Check(State.INFO, title, context.getString(R.string.check_connection_never));

        String refusal = HelperState.lastRefusal;
        if (refusal == null)
            return Collections.singletonList(state);
        List<Check> checks = new ArrayList<>(2);
        checks.add(state);
        checks.add(new Check(State.WARN, context.getString(R.string.check_refused),
            context.getString(R.string.check_refused_detail, refusal, time(context, HelperState.lastRefusedAt))));
        return checks;
    }

    private static String time(Context context, long millis) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(millis))
            flags |= DateUtils.FORMAT_SHOW_DATE;
        return DateUtils.formatDateTime(context, millis, flags);
    }

    private static PackageInfo packageInfo(PackageManager packages, String name) {
        try {
            return packages.getPackageInfo(name, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
