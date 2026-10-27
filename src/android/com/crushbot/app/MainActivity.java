package com.crushbot.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.crushbot.core.Bot;

/** Setup, start/stop, settings and the log. */
public final class MainActivity extends Activity {
    TextView status, stats, log;
    Button start, pause, stop, setup;
    EditText maxLevels;
    ScrollView logScroll;
    SharedPreferences prefs;
    final Runnable refresh = this::refresh;
    static final int REQ_CAPTURE = 1;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("crushbot", Context.MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            android.graphics.Insets bars = in.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return in;
        });
        if (getActionBar() != null) getActionBar().hide();

        TextView title = new TextView(this);
        title.setText("Crushbot");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Plays Crown Crush by itself: holds to smash the tower, lets go before a cursed stone, "
                + "then moves on to the next level. It never presses the video-ad buttons.");
        sub.setPadding(0, 0, 0, pad / 2);
        root.addView(sub);

        status = new TextView(this);
        status.setTextSize(15);
        root.addView(status);
        setup = new Button(this);
        setup.setText("Turn on the Crushbot player (Accessibility)");
        setup.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(setup);

        LinearLayout buttons = new LinearLayout(this);
        start = button("Start", v -> startBot(0, false));
        pause = button("Pause", v -> {
            BotService s = BotService.instance;
            if (s != null && s.bot != null) s.bot.pause(!s.bot.paused());
            refresh();
        });
        stop = button("Stop", v -> {
            BotService s = BotService.instance;
            if (s != null) s.stopBot();
        });
        buttons.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(pause, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        TextView note = new TextView(this);
        note.setText("Start asks to share the screen (pick \"Entire screen\"): the bot has to see every frame "
                + "to time its moves.");
        root.addView(note);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView gl = new TextView(this);
        gl.setText("Stop after this many levels (0 = keep going): ");
        maxLevels = new EditText(this);
        maxLevels.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxLevels.setText(String.valueOf(prefs.getInt("maxLevels", 0)));
        maxLevels.setMinEms(3);
        row.addView(gl);
        row.addView(maxLevels);
        root.addView(row);

        stats = new TextView(this);
        stats.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(stats);

        logScroll = new ScrollView(this);
        log = new TextView(this);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(11);
        log.setTextIsSelectable(true);
        logScroll.addView(log);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    boolean probe;

    void startBot(int tries, boolean probe) {
        this.probe = probe;
        BotService s = BotService.instance;
        if (s == null) {
            // Right after an update the service takes a moment to reconnect.
            String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on != null && on.contains(getPackageName()) && tries < 10) {
                status.postDelayed(() -> startBot(tries + 1, probe), 500);
                return;
            }
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (CaptureService.instance == null) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 2);
            android.media.projection.MediaProjectionManager mpm = getSystemService(android.media.projection.MediaProjectionManager.class);
            startActivityForResult(mpm.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()), REQ_CAPTURE);
            return; // carries on in onActivityResult
        }
        launch(s);
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_CAPTURE) return;
        BotService s = BotService.instance;
        if (s == null) return;
        if (result == RESULT_OK && data != null) {
            startForegroundService(new Intent(this, CaptureService.class).putExtra("code", result).putExtra("data", data));
            launch(s);
        } else {
            s.log("screen sharing declined: the bot can't time its moves without it");
        }
    }

    void launch(BotService s) {
        Bot.Config cfg = new Bot.Config();
        int n = 0;
        try {
            n = Integer.parseInt(maxLevels.getText().toString().trim());
        } catch (NumberFormatException ignored) { }
        cfg.maxLevels = Math.max(0, n);
        cfg.apply(prefs.getString("tune", ""));
        cfg.probe = probe;
        prefs.edit().putInt("maxLevels", cfg.maxLevels).apply();
        s.log("settings: " + cfg);
        // Hand the screen to the game first; the bot waits for the tower.
        s.launchGame();
        s.startBot(cfg);
    }

    @Override protected void onResume() {
        super.onResume();
        BotService.onChange = refresh;
        refresh();
        // For scripting over adb:
        //   am start -n com.crushbot.app/.MainActivity --ez start true [--ei levels N] [--es tune "a=1,b=2"] [--ez probe true]
        Intent i = getIntent();
        if (i != null && i.hasExtra("levels")) {
            maxLevels.setText(String.valueOf(i.getIntExtra("levels", 0)));
            prefs.edit().putInt("maxLevels", i.getIntExtra("levels", 0)).apply();
            i.removeExtra("levels");
        }
        // Capture size as a fraction of the screen: --ef capScale 0.5
        if (i != null && i.hasExtra("capScale")) {
            prefs.edit().putFloat("capScale", i.getFloatExtra("capScale", 0.5f)).apply();
            i.removeExtra("capScale");
        }
        if (i != null && i.hasExtra("tune")) {
            prefs.edit().putString("tune", i.getStringExtra("tune")).apply();
            i.removeExtra("tune");
        }
        if (i != null && i.getBooleanExtra("start", false)) {
            boolean pr = i.getBooleanExtra("probe", false);
            i.removeExtra("start");
            i.removeExtra("probe");
            startBot(0, pr);
        }
        if (i != null && i.getBooleanExtra("stop", false)) {
            i.removeExtra("stop");
            if (BotService.instance != null) BotService.instance.stopBot();
            refresh();
        }
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
    }

    @Override protected void onPause() {
        super.onPause();
        BotService.onChange = null;
    }

    void refresh() {
        BotService s = BotService.instance;
        boolean on = s != null;
        boolean running = on && s.isRunning();
        setup.setVisibility(on ? View.GONE : View.VISIBLE);
        status.setText(!on ? "Setup: the Crushbot player accessibility service is off."
                : running ? "Bot: " + BotService.state : "Ready. Press Start.");
        start.setEnabled(on && !running);
        pause.setEnabled(running);
        pause.setText(running && s.bot.paused() ? "Resume" : "Pause");
        stop.setEnabled(running);
        Bot.Stats st = on ? s.stats() : null;
        if (st != null && st.levels + st.deaths > 0) {
            long mins = Math.max(1, (System.currentTimeMillis() - st.started) / 60000);
            stats.setText(String.format("Levels won: %d   lost: %d   ~%d levels/hour", st.levels, st.deaths,
                    st.levels * 60 / mins));
        } else stats.setText("");
        StringBuilder sb = new StringBuilder();
        synchronized (BotService.LOG) {
            for (String line : BotService.LOG) sb.append(line).append('\n');
        }
        log.setText(sb);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }
}
