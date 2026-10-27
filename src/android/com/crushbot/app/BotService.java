package com.crushbot.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import com.crushbot.core.Bot;
import com.crushbot.core.Frame;
import com.crushbot.core.Img;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The accessibility service is how the bot touches the screen (gestures, including a finger held
 * down for as long as it likes). It also owns the bot thread and a small floating pause/stop button.
 */
public final class BotService extends AccessibilityService implements Bot.Device, Bot.Listener {
    static volatile BotService instance;

    /** Log lines and state, shown by MainActivity. */
    static final ArrayDeque<String> LOG = new ArrayDeque<>();
    static volatile String state = "stopped";
    static volatile Runnable onChange;

    final Handler main = new Handler(Looper.getMainLooper());
    Bot bot;
    Thread thread;
    volatile String lastPackage;
    TextView pill;

    @Override protected void onServiceConnected() {
        instance = this;
        log("player service connected");
    }

    @Override public boolean onUnbind(Intent intent) {
        stopBot();
        removePill();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null)
            lastPackage = e.getPackageName().toString();
    }

    @Override public void onInterrupt() { }

    // ---------------------------------------------------------------- control

    boolean isRunning() { return bot != null && bot.running(); }

    void startBot(Bot.Config cfg) {
        if (isRunning()) {
            stopBot();
            try {
                thread.join(3000);
            } catch (InterruptedException ignored) { }
        }
        bot = new Bot(this, this, cfg);
        thread = new Thread(bot::run, "crushbot");
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
        showPill();
    }

    void stopBot() {
        if (bot != null) bot.stop();
        if (thread != null) thread.interrupt();
    }

    Bot.Stats stats() { return bot == null ? null : bot.stats; }

    // ---------------------------------------------------------------- Bot.Listener

    @Override public void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg;
        synchronized (LOG) {
            LOG.addLast(line);
            while (LOG.size() > 400) LOG.removeFirst();
        }
        android.util.Log.i("Crushbot", msg);
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    @Override public void state(String s) {
        state = s;
        main.post(this::updatePill);
        if (s.equals("stopped")) main.post(() -> {
            removePill();
            CaptureService.stop(this);
        });
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    // ---------------------------------------------------------------- Bot.Device

    @Override public <T> T look(long afterT, long timeoutMs, Function<Frame, T> fn) throws Exception {
        CaptureService c = CaptureService.instance;
        if (c == null) {
            Thread.sleep(Math.min(timeoutMs, 200));
            return null;
        }
        c.keepCopies = keepFrames;
        return c.look(afterT, timeoutMs, fn);
    }

    volatile boolean keepFrames;

    @Override public void keepFrames(boolean on) {
        keepFrames = on;
    }

    @Override public Img lastFrame() {
        CaptureService c = CaptureService.instance;
        return c == null ? null : c.lastCopy();
    }

    @Override public String captureStats() {
        CaptureService c = CaptureService.instance;
        return c == null ? "off" : c.frames + " frames so far";
    }

    /** The stroke of the finger that's down, to continue (lift) later. */
    GestureDescription.StrokeDescription held;
    int heldX, heldY;

    @Override public synchronized void down(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription s = new GestureDescription.StrokeDescription(p, 0, 1, true);
        held = s;
        heldX = x;
        heldY = y;
        dispatchGesture(new GestureDescription.Builder().addStroke(s).build(), null, null);
    }

    @Override public synchronized void up() {
        GestureDescription.StrokeDescription s = held;
        if (s == null) return;
        held = null;
        Path p = new Path();
        p.moveTo(heldX, heldY);
        dispatchGesture(new GestureDescription.Builder().addStroke(s.continueStroke(p, 0, 1, false)).build(), null, null);
    }

    boolean gesture(GestureDescription g) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] ok = {false};
        main.post(() -> {
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) {
                    ok[0] = true;
                    done.countDown();
                }

                @Override public void onCancelled(GestureDescription d) {
                    done.countDown();
                }
            }, main);
            if (!sent) done.countDown();
        });
        done.await(5, TimeUnit.SECONDS);
        return ok[0];
    }

    @Override public void tap(int x, int y) throws Exception {
        synchronized (this) {
            held = null;
        }
        Path p = new Path();
        p.moveTo(x, y);
        gesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build());
    }

    @Override public void back() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override public String foreground() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String p = root.getPackageName().toString();
                if (!p.equals(getPackageName())) return p;
            }
        } catch (Exception ignored) { }
        String p = lastPackage;
        return p == null || p.equals(getPackageName()) ? null : p;
    }

    @Override public boolean pressText(String text) {
        java.util.List<AccessibilityNodeInfo> roots = new java.util.ArrayList<>();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows())
                if (w.getRoot() != null) roots.add(w.getRoot());
        } catch (Exception ignored) { }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        if (active != null) roots.add(active);
        for (AccessibilityNodeInfo root : roots)
            for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(text)) {
                if (n.getText() == null || !n.getText().toString().trim().equalsIgnoreCase(text)) continue;
                for (AccessibilityNodeInfo c = n; c != null; c = c.getParent())
                    if (c.isClickable()) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
        return false;
    }

    @Override public void launchGame() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Bot.GAME);
        if (i == null) {
            log("Crown Crush isn't installed");
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** Saves to /sdcard/Android/data/com.crushbot.app/files/debug/, keeping the last 40. */
    @Override public void save(Img im, String name) {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "debug");
            dir.mkdirs();
            java.io.File[] old = dir.listFiles();
            if (old != null && old.length >= 40) {
                java.util.Arrays.sort(old, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i <= old.length - 40; i++) old[i].delete();
            }
            Bitmap bm = Bitmap.createBitmap(im.px, im.w, im.h, Bitmap.Config.ARGB_8888);
            try (java.io.FileOutputStream f = new java.io.FileOutputStream(
                    new java.io.File(dir, name + "-" + System.currentTimeMillis() + ".png"))) {
                bm.compress(Bitmap.CompressFormat.PNG, 100, f);
            }
            bm.recycle();
        } catch (Exception e) {
            log("couldn't save a screenshot: " + e);
        }
    }

    // ---------------------------------------------------------------- floating button

    void showPill() {
        main.post(() -> {
            if (pill != null) return;
            pill = new TextView(this);
            pill.setTextColor(Color.WHITE);
            pill.setTextSize(13);
            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            pill.setPadding(pad * 3 / 2, pad, pad * 3 / 2, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(100);
            bg.setColor(0xCC1E7A46);
            pill.setBackground(bg);
            pill.setOnClickListener(v -> {
                if (bot != null) bot.pause(!bot.paused());
            });
            pill.setOnLongClickListener(v -> {
                stopBot();
                return true;
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            // Top middle, over the status bar: clear of the game's buttons and the tower.
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(pill, lp);
            updatePill();
        });
    }

    void updatePill() {
        if (pill == null) return;
        boolean paused = bot != null && bot.paused();
        pill.setText(paused ? "▶" : "⏸");
        ((GradientDrawable) pill.getBackground()).setColor(paused ? 0xCC8A6D00 : 0xCC1E7A46);
    }

    void removePill() {
        if (pill == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(pill);
        } catch (Exception ignored) { }
        pill = null;
    }
}
