package dev.mooner.kakaodb.sharedd;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import dev.mooner.kakaodb.rootd.Protocol;

import java.util.List;

/**
 * The helper's status screen: whether it is patched into KakaoTalk's UID, whether it can see the databases, and
 * whether StarLight is connected. The helper works without it; launching it once also takes a fresh install out of
 * Android's stopped state.
 */
public final class MainActivity extends Activity {

    /** The connection changes while the screen is open: it is checked again this often. */
    private static final long REFRESH_MILLIS = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MILLIS);
        }
    };

    private TextView summaryBadge;
    private TextView summaryTitle;
    private TextView summaryDetail;
    private ViewGroup setupList;
    private ViewGroup connectionList;
    private Button openKakao;
    private Button openStarLight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        summaryBadge = findViewById(R.id.summary_badge);
        summaryTitle = findViewById(R.id.summary_title);
        summaryDetail = findViewById(R.id.summary_detail);
        setupList = findViewById(R.id.setup_list);
        connectionList = findViewById(R.id.connection_list);
        openKakao = findViewById(R.id.open_kakao);
        openStarLight = findViewById(R.id.open_starlight);
        openKakao.setOnClickListener(v -> launch(Protocol.KAKAO_PACKAGE));
        openStarLight.setOnClickListener(v -> launch(Protocol.STARLIGHT_PACKAGE));

        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView footer = findViewById(R.id.footer);
        footer.setText(getString(R.string.footer, BuildConfig.SHARED_HELPER_BUILD, Protocol.VERSION, version));
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    private void refresh() {
        HelperChecks.Report report = HelperChecks.run(this);
        bindBadge(summaryBadge, report.overall);
        summaryTitle.setText(report.headline);
        summaryDetail.setText(report.summary);
        bindRows(setupList, report.setup);
        bindRows(connectionList, report.connection);
        openKakao.setEnabled(launchIntent(Protocol.KAKAO_PACKAGE) != null);
        openStarLight.setEnabled(launchIntent(Protocol.STARLIGHT_PACKAGE) != null);
    }

    /** Reuses the rows already there, so refreshing doesn't rebuild the list. */
    private void bindRows(ViewGroup list, List<HelperChecks.Check> checks) {
        LayoutInflater inflater = getLayoutInflater();
        while (list.getChildCount() > checks.size())
            list.removeViewAt(list.getChildCount() - 1);
        while (list.getChildCount() < checks.size())
            list.addView(inflater.inflate(R.layout.item_check, list, false));
        for (int i = 0; i < checks.size(); i++) {
            HelperChecks.Check check = checks.get(i);
            View row = list.getChildAt(i);
            bindBadge(row.findViewById(R.id.badge), check.state);
            ((TextView) row.findViewById(R.id.title)).setText(check.title);
            ((TextView) row.findViewById(R.id.detail)).setText(check.detail);
        }
    }

    private void bindBadge(TextView badge, HelperChecks.State state) {
        int color;
        int symbol;
        int description;
        switch (state) {
            case OK:
                color = getColor(R.color.kdb_ok);
                symbol = R.string.symbol_ok;
                description = R.string.state_ok;
                break;
            case WARN:
                color = getColor(R.color.kdb_warn);
                symbol = R.string.symbol_warn;
                description = R.string.state_warn;
                break;
            case ERROR:
                color = getColor(R.color.kdb_error);
                symbol = R.string.symbol_error;
                description = R.string.state_error;
                break;
            default:
                color = getColor(R.color.kdb_info);
                symbol = R.string.symbol_info;
                description = R.string.state_info;
                break;
        }
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        // The state's color, faint, behind its symbol in full: readable in light and dark themes alike.
        circle.setColor(ColorStateList.valueOf((color & 0x00ffffff) | 0x29000000));
        badge.setBackground(circle);
        badge.setTextColor(color);
        badge.setText(symbol);
        badge.setContentDescription(getString(description));
    }

    private Intent launchIntent(String packageName) {
        return getPackageManager().getLaunchIntentForPackage(packageName);
    }

    private void launch(String packageName) {
        Intent intent = launchIntent(packageName);
        if (intent != null)
            startActivity(intent);
    }
}
