package com.crushbot.core;

import java.util.function.Function;

/** The bot: watches the screen and plays Crown Crush. */
public final class Bot {
    public static final String GAME = "com.gl.crowncrush";

    public interface Device {
        /** Waits (up to timeoutMs) for a frame newer than afterT and runs fn on it. Null on timeout. */
        <T> T look(long afterT, long timeoutMs, Function<Frame, T> fn) throws Exception;
        /** Puts a finger down here and keeps it down. */
        void down(int x, int y);
        /** Lifts the finger down() put down. */
        void up();
        void tap(int x, int y) throws Exception;
        void back();
        /** Package of the app in front, or null if unknown. */
        String foreground();
        void launchGame() throws Exception;
        /** Keeps a screenshot for looking at later. */
        void save(Img im, String name);
        default boolean pressText(String text) { return false; }
        /** Keep a copy of each frame read, for lastFrame(). */
        default void keepFrames(boolean on) { }
        /** The last frame read (at capture size), or null. */
        default Img lastFrame() { return null; }
        /** Capture counters, for timing tests. */
        default String captureStats() { return ""; }
    }

    public interface Listener {
        void log(String msg);
        void state(String s);
    }

    public static final class Config {
        public int maxLevels = 0;      // 0 = no limit
        public boolean probe = false;  // measure timings instead of playing
        public int delay = 2;          // game frames from sending a touch to the game acting on it
        public double margin = 4;      // degrees of safety either side of the ball's window
        public boolean verbose = false; // log every frame
        public boolean saveScreens = false; // keep a picture of each screen the bot presses on

        public void apply(String tune) {
            if (tune == null) return;
            for (String kv : tune.split(",")) {
                String[] p = kv.trim().split("=");
                if (p.length != 2) continue;
                try {
                    java.lang.reflect.Field f = Config.class.getField(p[0].trim());
                    if (f.getType() == int.class) f.setInt(this, Integer.parseInt(p[1].trim()));
                    else if (f.getType() == boolean.class) f.setBoolean(this, Boolean.parseBoolean(p[1].trim()));
                    else if (f.getType() == double.class) f.setDouble(this, Double.parseDouble(p[1].trim()));
                } catch (Exception ignored) { }
            }
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Field f : Config.class.getFields()) {
                try {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(f.getName()).append('=').append(f.get(this));
                } catch (Exception ignored) { }
            }
            return sb.toString();
        }
    }

    public static final class Stats {
        public int levels, deaths, graces;
        public long started = System.currentTimeMillis();
    }

    final Device dev;
    final Listener out;
    final Config cfg;
    public final Stats stats = new Stats();
    volatile boolean running = true, paused;

    public Bot(Device dev, Listener out, Config cfg) {
        this.dev = dev;
        this.out = out;
        this.cfg = cfg;
    }

    public boolean running() { return running; }
    public boolean paused() { return paused; }
    public void pause(boolean p) { paused = p; out.state(p ? "paused" : "playing"); }
    public void stop() { running = false; }

    void log(String s) { out.log(s); }

    static final int TOUCH_X = 540, TOUCH_Y = 1500;

    public void run() {
        out.state("playing");
        try {
            if (cfg.probe) probe();
            else play();
        } catch (InterruptedException e) {
            // stopped
        } catch (Throwable e) {
            log("error: " + e);
            for (StackTraceElement el : e.getStackTrace()) log("  at " + el);
        } finally {
            try {
                dev.up();
            } catch (Throwable ignored) { }
            running = false;
            out.state("stopped");
        }
    }

    // ------------------------------------------------------------------ playing

    final Player pl = new Player();
    boolean down;
    long lastT, downAt, lastSmashT, offSince, lastScreenAt, lastFrameAt = System.currentTimeMillis();
    int missed, weak;

    /** The finger has been down long enough to have held through the previous frame too. */
    boolean heldSince(long t) {
        return down && t - downAt > 2 * pl.periodHi() * 1e9;
    }

    void press(boolean want) {
        if (want == down) return;
        if (want) dev.down(TOUCH_X, TOUCH_Y);
        else dev.up();
        down = want;
        if (want) downAt = System.nanoTime();
    }

    void play() throws Exception {
        pl.delay = cfg.delay;
        pl.margin = cfg.margin;
        dev.keepFrames(cfg.saveScreens);
        if (!GAME.equals(dev.foreground())) {
            dev.launchGame();
            Thread.sleep(2500);
        }
        int levelsAtStart = 0;
        while (running) {
            if (cfg.maxLevels > 0 && stats.levels - levelsAtStart >= cfg.maxLevels) {
                log("levels: done (" + stats.levels + ")");
                break;
            }
            if (paused) {
                press(false);
                Thread.sleep(200);
                continue;
            }
            Eye.Look k = dev.look(lastT, 1500, Eye::read);
            if (k == null) {
                // Nothing new on screen for a while: a still screen.
                press(false);
                screen(null);
                continue;
            }
            lastT = k.t;
            if (!k.tower) {
                press(false);
                if (k.shifted && pl.prev != null) {
                    // A new stage sliding in: its plates and its turning start over.
                    log(String.format("next stage after %d plates", pl.smashes));
                    pl.reset();
                }
                long now = System.currentTimeMillis();
                if (offSince == 0) offSince = now;
                if (now - offSince > 1200 && pl.prev != null) newTower();
                // (A frame or two can fail to read: pieces of plates flying over the cap.)
                if (now - offSince > 600 && now - lastScreenAt > 700) screen(k);
                continue;
            }
            offSince = 0;
            unknownSince = 0;
            if (pl.prev != null && k.sum == pl.prev.sum) continue; // the same game frame again
            if (!k.atRest && pl.medianPeriod() >= 0.045) {
                // Under ~22 frames a second the camera always rests at one of two heights; any
                // other reading means pieces of plates hid the cap. Skip the frame, finger up.
                weak++;
                press(false);
                continue;
            }
            pl.see(k, down && heldSince(k.t));
            if (pl.note.contains("start over") && cfg.saveScreens) saveFrame("start-over");
            boolean smashed = pl.note.contains("SMASH");
            if (smashed) {
                lastSmashT = k.t;
                missed = 0;
            } else if (down && k.t - downAt > (cfg.delay + 0.5) * pl.periodHi() * 1e9) {
                // Held long enough that this frame should have smashed (if the game took the
                // touch: after a forgiven cursed stone or a new stage it waits for a new press).
                if (pl.periodLo() >= 0.08) missed++;
            }
            boolean want = pl.wantHold(down);
            if (down && want && missed >= 2) {
                log("held without smashing: pressing again");
                if (cfg.saveScreens) saveFrame("no-smash");
                want = false;
                missed = 0;
            }
            if (cfg.verbose)
                log(String.format("%s | %s -> %s", pl.note, k.brief().substring(0, Math.min(60, k.brief().length())),
                        want ? "HOLD" : "up"));
            press(want);
        }
    }

    // ------------------------------------------------------------------ screens

    long unknownSince;
    int backs;

    /** After pressing on: whatever tower comes next is a new one. */
    void newTower() {
        if (pl.prev != null)
            log(String.format("tower done: %d plates smashed in %d frames (%d skipped, %d fresh starts)", pl.smashes,
                    pl.frames, weak, pl.resets));
        weak = 0;
        pl.reset();
        pl.smashes = 0;
        pl.frames = 0;
        pl.resets = 0;
    }

    /** Not a tower in play: the won or lost screen, the title, a card, an ad... */
    void screen(Eye.Look k) throws Exception {
        long now = System.currentTimeMillis();
        lastScreenAt = now;
        String fg = dev.foreground();
        if (fg != null && !fg.equals(GAME)) {
            // The system's "isn't responding" and permission dialogs.
            if (dev.pressText("Wait") || dev.pressText("Don't allow") || dev.pressText("Don’t allow")) {
                log("answered a system dialog over " + fg);
                return;
            }
            if (!fg.startsWith("com.android.systemui") && !fg.equals("com.google.android.permissioncontroller")) {
                if (now - unknownSince > 3000 && unknownSince != 0) {
                    log(fg + " is in front: back to the game");
                    dev.launchGame();
                    unknownSince = 0;
                    return;
                }
            }
        }
        Img im = dev.look(0, 500, Img::of);
        if (im == null) return;
        if (k == null) k = Eye.read(im);
        java.util.List<Screens.Box> bs = Screens.orangeButtons(im);
        Screens.Box main = Screens.mainButton(bs);
        if (Screens.darkVeil(im) && !bs.isEmpty()) {
            Screens.Box b = bs.get(0);
            log("a card over the tower: pressing its button " + b);
            save(im, "card");
            dev.tap(b.cx(), b.cy());
            Thread.sleep(1000);
            unknownSince = 0;
            newTower();
            return;
        }
        if (k != null && k.pale) {
            if (main != null) {
                stats.levels++;
                log(String.format("level won (%d so far): pressing the next level %s", stats.levels, main));
                save(im, "won");
                dev.tap(main.cx(), main.cy());
            } else if (Screens.lostHeadline(im)) {
                int[] at = Screens.restart(bs);
                stats.deaths++;
                log(String.format("lost (%d so far): restarting at %d,%d", stats.deaths, at[0], at[1]));
                save(im, "lost");
                dev.tap(at[0], at[1]);
            } else {
                if (unknownSince == 0) {
                    unknownSince = now;
                    save(im, "unknown-veiled");
                    log("a veiled tower that isn't the won or lost screen: waiting");
                }
                return;
            }
            Thread.sleep(1500);
            unknownSince = 0;
            newTower();
            return;
        }
        if (main != null) {
            log("pressing the main button " + main);
            save(im, "main");
            dev.tap(main.cx(), main.cy());
            Thread.sleep(1500);
            unknownSince = 0;
            newTower();
            return;
        }
        if (Screens.streakSeal(im)) {
            log("daily streak: stamping the seal");
            save(im, "streak");
            dev.tap(540, 812);
            Thread.sleep(1500);
            unknownSince = 0;
            newTower();
            return;
        }
        int[] fresh = Screens.startFresh(bs);
        if (fresh != null && !Screens.darkVeil(im)) {
            log("the daily streak broke: starting fresh (not the video to repair it)");
            save(im, "streak-fresh");
            dev.tap(fresh[0], fresh[1]);
            Thread.sleep(1500);
            unknownSince = 0;
            newTower();
            return;
        }
        if (unknownSince == 0) {
            unknownSince = now;
            backs = 0;
            save(im, "unknown");
            log("a screen I don't know: waiting (orange buttons " + bs + ")");
            return;
        }
        long waited = now - unknownSince;
        if (waited > 10000 + 8000L * backs) {
            backs++;
            log("still on an unknown screen after " + waited / 1000 + " s: pressing back");
            save(im, "unknown-back");
            dev.back();
        }
    }

    long lastSave;

    void saveFrame(String name) {
        Img im = dev.lastFrame();
        if (im != null) dev.save(im, name);
    }

    void save(Img im, String name) {
        if (!cfg.saveScreens) return;
        dev.save(im, name);
    }

    // ------------------------------------------------------------------ timing probe

    /** Presses and lets go a few times, logging what each frame shows, to measure delays. */
    void probe() throws Exception {
        long last = 0;
        long t0 = System.nanoTime();
        int frames = 0;
        long readNs = 0;
        // Frame rate and reading cost first.
        log("probe: capture " + dev.captureStats());
        long[] inside = new long[1];
        while (System.nanoTime() - t0 < 2_000_000_000L) {
            Eye.Look k = dev.look(last, 500, f -> {
                long r0 = System.nanoTime();
                Eye.Look kk = Eye.read(f);
                inside[0] += System.nanoTime() - r0;
                return kk;
            });
            if (k == null) continue;
            frames++;
            last = k.t;
        }
        log(String.format("probe: %d frames read in 2 s, reading %.1f ms each; capture %s", frames,
                inside[0] / 1e6 / Math.max(1, frames), dev.captureStats()));
        // Input delay in frames: press right after a frame arrives, then count frames until the
        // first smash shows (the top plate's odd/even shade flips); the same for letting go.
        for (int cycle = 0; cycle < 8 && running; cycle++) {
            Eye.Look k0 = null;
            while (k0 == null || !k0.tower) {
                k0 = dev.look(last, 1000, Eye::read);
                if (k0 != null) last = k0.t;
            }
            boolean down = cycle % 2 == 0;
            if (down) dev.down(TOUCH_X, TOUCH_Y);
            else dev.up();
            long cmd = System.nanoTime();
            StringBuilder sb = new StringBuilder(String.format("probe %s %.0f ms after frame:", down ? "DOWN" : "UP",
                    (cmd - k0.t) / 1e6));
            int par = k0.parity;
            double y = k0.y0;
            for (int f = 1; f <= 6; f++) {
                Eye.Look k = dev.look(last, 1500, Eye::read);
                if (k == null) break;
                last = k.t;
                if (!k.tower) {
                    sb.append(" -");
                    continue;
                }
                boolean smash = (par >= 0 && k.parity >= 0 && k.parity != par) || (k.y0 - Eye.TOP_Y > 30 && k.y0 - y > -8);
                sb.append(String.format(" f%d@%.0f%s", f, (k.t - cmd) / 1e6, smash ? "*" : ""));
                if (k.parity >= 0) par = k.parity;
                y = k.y0;
            }
            log(sb.toString());
            if (!down) Thread.sleep(400);
        }
    }
}
