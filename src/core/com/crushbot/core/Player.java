package com.crushbot.core;

import java.util.ArrayList;

/**
 * Plays one tower. Frame by frame it keeps track of how far the tower has turned and how many
 * plates are gone, remembers what every segment of every plate looked like, and decides whether
 * to hold.
 *
 * Timing (measured on the emulator): a touch sent just after a frame shows up takes effect two
 * game frames later; the next frame is already on its way. While held, the game smashes one
 * plate per frame (at most), at the rotation that frame is drawn with. So after seeing frame f
 * the bot decides about frame f + 2: hold only if the plate that will be on top then is safe at
 * every angle the tower can have turned to by then.
 */
final class Player {
    static final int N = 12;
    /** Frames between sending a touch and the game acting on it. */
    int delay = 2;
    /** Extra degrees of safety either side of the ball's 81..99 degree window. */
    double margin = 4;

    /** What each segment of a plate looked like: counts of cursed and safe sightings. */
    static final class Plate {
        final short[] c = new short[N], s = new short[N]; // all sightings
        final short[] cn = new short[N], sn = new short[N]; // while the plate was near the top
    }

    final ArrayList<Plate> plates = new ArrayList<>();
    /** Plates smashed this stage: the index of the plate on top. */
    int b;
    /** Degrees, unwrapped. Segment label L spans [theta + 30 L, theta + 30 L + 30). */
    double theta;
    /** Degrees per second; NaN until two frames agree. */
    double omega = Double.NaN;
    int omegaVotes;
    Eye.Look prev;
    final double[] periods = new double[12];
    int np;
    int frames, smashes, resets;
    int lastSm = -1;
    boolean lastHeld;
    String note = "";

    /** In a fever cursed stones don't count; it drains over 3.2 game seconds from a full bar. */
    boolean fever;
    double feverLevel = -1;
    static final double FEVER_TIME = 3.2;

    /**
     * A fever starts when the bar is full (charging, it can stop just short of full), and then
     * the bar only drains: so a fever is a full bar followed by a slightly shorter one.
     */
    void trackFever(Eye.Look k) {
        if (k.fever < 0) return;
        if (fever) {
            if (k.fever <= 0.02 || k.fever > feverLevel + 0.01) fever = false;
        } else if (feverLevel >= 0.995 && k.fever < feverLevel - 0.005 && k.fever > feverLevel - 0.15) fever = true;
        feverLevel = k.fever;
    }

    /** Still in a fever for the frame the next touch lands in, with some to spare. */
    boolean feverHolds() {
        if (!fever || feverLevel < 0) return false;
        double drain = (delay + 1.5) * gameTime(periodHi()) / FEVER_TIME;
        return feverLevel - drain > 0.05;
    }

    void reset() {
        if (prev != null) resets++;
        fever = false;
        feverLevel = -1;
        plates.clear();
        b = 0;
        omega = Double.NaN;
        omegaVotes = 0;
        prev = null;
        lastSm = -1;
    }

    static double wrap30(double d) {
        d = ((d % 30) + 30) % 30;
        return d > 15 ? d - 30 : d;
    }

    static int mod12(long v) {
        return (int) (((v % N) + N) % N);
    }

    /** Label of the segment starting at phi + 30 r, with the tower turned to th. */
    static int label(int r, double phi, double th) {
        return mod12(Math.round((phi - th) / 30) + r);
    }

    Plate plate(int p) {
        while (plates.size() <= p) plates.add(new Plate());
        return plates.get(p);
    }

    boolean smashed(Eye.Look a, Eye.Look k) {
        boolean lag = k.y0 - Eye.TOP_Y > 30 && k.y0 - a.y0 > -8;
        if (a.parity >= 0 && k.parity >= 0) return a.parity != k.parity;
        return lag;
    }

    /** How well frame k fits what's remembered, if the tower is at th and plate nb is on top. */
    double match(Eye.Look k, int nb, double th) {
        double score = 0;
        for (int j = 0; j < k.plates; j++) {
            int p = nb + j;
            if (p >= plates.size()) break;
            Plate pl = plates.get(p);
            for (int r = -1; r <= 5; r++) {
                byte o = k.seg[j][r + 1];
                if (o == Eye.UNKNOWN) continue;
                int L = label(r, k.phi, th);
                int c = pl.c[L], s = pl.s[L];
                if (c + s < 2) continue;
                double q = c / (double) (c + s);
                if (o == Eye.CURSED) score += q > 0.5 ? 1 : q < 0.15 ? -1 : 0;
                else if (q > 0.6) score -= 0.5;
            }
        }
        return score;
    }

    void record(Eye.Look k) {
        for (int j = 0; j < k.plates; j++) {
            Plate pl = plate(b + j);
            for (int r = -1; r <= 5; r++) {
                byte o = k.seg[j][r + 1];
                if (o == Eye.UNKNOWN) continue;
                int L = label(r, k.phi, theta);
                if (o == Eye.CURSED) {
                    pl.c[L]++;
                    if (j <= 3) pl.cn[L]++;
                } else {
                    pl.s[L]++;
                    if (j <= 3) pl.sn[L]++;
                }
            }
        }
    }

    /**
     * Sightings that contradict what's remembered with confidence (a new stage, or lost track),
     * and sightings that confirm it: {contradicting, confirming}. Safe sightings of a cursed
     * segment only count further down: near the top, flying pieces of plates hide the curse.
     */
    int[] contradictions(Eye.Look k) {
        int bad = 0, good = 0;
        for (int j = 0; j < k.plates; j++) {
            int p = b + j;
            if (p >= plates.size()) break;
            Plate pl = plates.get(p);
            for (int r = -1; r <= 5; r++) {
                byte o = k.seg[j][r + 1];
                int L = label(r, k.phi, theta);
                boolean sureSafe = pl.s[L] >= 5 && pl.c[L] == 0, sureCursed = pl.c[L] >= 5 && pl.s[L] * 4 <= pl.c[L];
                if (o == Eye.CURSED && sureSafe) bad++;
                if (o == Eye.SAFE && sureCursed && j >= 5) bad++;
                if (o == Eye.CURSED && sureCursed) good++;
                if (o == Eye.SAFE && sureSafe) good++;
            }
        }
        return new int[] {bad, good};
    }

    /**
     * The game moves on by the frame's real duration, but at most 8/60 s (Godot runs at most 8
     * physics steps a frame): on a slow emulator the game runs in slow motion, the same step
     * every frame.
     */
    static final double GAME_DT_MAX = 8.0 / 60;

    static double gameTime(double dt) {
        return Math.min(dt, GAME_DT_MAX);
    }

    double medianPeriod() {
        int n = Math.min(np, periods.length);
        if (n == 0) return 0;
        double[] a = java.util.Arrays.copyOf(periods, n);
        java.util.Arrays.sort(a);
        return a[n / 2];
    }

    /** Takes in a frame of a tower in play; held: the finger was down since the last one. */
    void see(Eye.Look k, boolean held) {
        frames++;
        trackFever(k);
        if (prev == null) {
            theta = k.phi;
            record(k);
            prev = k;
            note = "first look";
            return;
        }
        double dt = (k.t - prev.t) / 1e9;
        if (dt <= 0) return;
        if (prev.remaining == 1 && np >= 3 && dt > 1.5 * medianPeriod() && (k.remaining < 0 || k.remaining > 3)) {
            // The tower's foot was in sight and now a full tower stands: the next stage (its
            // plates and its turning start over).
            reset();
            theta = k.phi;
            record(k);
            prev = k;
            note = "next stage";
            return;
        }
        if (dt < 1.0) periods[np++ % periods.length] = dt;
        // Game frames since the last one seen: usually one, more if some were missed. Each moves
        // the game on by gameTime(its length), so the turn is somewhere between one frame's worth
        // and all of dt's worth.
        double minPer = Math.max(0.03, Math.min(periodLo(), dt));
        int nf = (int) Math.max(1, Math.min(6, Math.floor(dt / (minPer * 0.85))));
        double gt0 = gameTime(dt), gt1 = Math.min(dt, nf * GAME_DT_MAX);
        if (nf == 1) gt1 = gt0;
        double gt = (gt0 + gt1) / 2;
        // Plates smashed meanwhile: at most one a frame; the top plate's shade (odd or even)
        // and the camera (68 px low right after a smash) narrow it down.
        boolean lagSmash = k.y0 - Eye.TOP_Y > 30 && k.y0 - prev.y0 > -8;
        int flip = prev.parity >= 0 && k.parity >= 0 ? (prev.parity != k.parity ? 1 : 0) : -1;
        // The fever bar grows 0.085 a smash (with no fever on, and no bounce to drain it: the
        // finger stayed down). The bar is drawn before the tower in each frame, so it tells the
        // smashes of the interval before this one: a late check on that count.
        String fix = "";
        if (held && lastHeld && !fever && prev.fever >= 0 && k.fever >= 0 && k.fever < 0.99 && lastSm >= 0) {
            double est = (k.fever - prev.fever) / 0.085;
            long n = Math.round(est);
            if (est > -0.3 && Math.abs(est - n) < 0.3 && n != lastSm && Math.abs(n - lastSm) <= 2) {
                b += (int) (n - lastSm);
                smashes += (int) (n - lastSm);
                fix = " fever-fix" + (n - lastSm > 0 ? "+" : "") + (n - lastSm);
            }
        }
        double base = wrap30(k.phi - prev.phi);
        double reach = 230 * gt1 + 20; // the fastest towers turn about 200 degrees a game second
        double best = -1e9, second = -1e9, bestD = base;
        int bestS = 0;
        for (int sm = 0; sm <= nf + 2; sm++) {
            // The shade and the camera usually agree; a misread one costs, it doesn't rule out.
            // More plates than frames can only be catching up on an earlier miscount.
            double pen = sm > nf ? 2.5 * (sm - nf) : 0;
            if (flip >= 0 && (sm & 1) != flip) pen += 3;
            if (nf == 1 && (sm == 1) != lagSmash) pen += 3;
            if (lagSmash && sm == 0) pen += 3;
            for (int n = -12; n <= 12; n++) {
                double d = base + 30 * n;
                if (Math.abs(d) > reach) continue;
                double sc = match(k, b + sm, theta + d) - pen;
                if (Double.isNaN(omega)) sc -= Math.abs(d) / 40;
                else {
                    double e0 = omega * gt0, e1 = omega * gt1;
                    double off = Math.max(0, Math.max(Math.min(e0, e1) - d, d - Math.max(e0, e1)));
                    sc -= off / 12;
                }
                if (sc > best) {
                    second = best;
                    best = sc;
                    bestD = d;
                    bestS = sm;
                } else if (sc > second) second = sc;
            }
        }
        double d = bestD;
        theta += d;
        theta = k.phi + 30 * Math.round((theta - k.phi) / 30); // no drift
        b += bestS;
        smashes += bestS;
        lastSm = bestS;
        lastHeld = held;
        // The speed: d over the game time of the number of frames that fits d best.
        int m = 1;
        if (!Double.isNaN(omega))
            for (int mm = 2; mm <= nf; mm++)
                if (Math.abs(d - omega * mm * gameTime(dt / mm)) < Math.abs(d - omega * m * gameTime(dt / m))) m = mm;
        double w = d / (m * gameTime(dt / m));
        boolean sure = best - second >= 1.5;
        if (gt0 > 0.01) {
            if (Double.isNaN(omega) || Math.abs(w - omega) > 40) {
                omega = w;
                omegaVotes = sure ? 1 : 0;
            } else {
                omega = 0.6 * omega + 0.4 * w;
                if (sure) omegaVotes++;
            }
        }
        int[] cg = contradictions(k);
        int bad = cg[0];
        note = String.format("dt=%.0f%s d=%.1f (%.1f vs %.1f) w=%.0f b=%d%s%s", dt * 1000, nf > 1 ? "/" + nf : "", d,
                best, second, omega, b, bestS > 0 ? " SMASH" + (bestS > 1 ? "x" + bestS : "") : "",
                bad > 0 ? " contra=" + bad : "") + (fever ? " FEVER" : "") + fix;
        if (bad >= 6 && bad * 4 >= cg[1]) {
            // A new stage (rotation and plates start over), or tracking went wrong: start afresh.
            reset();
            theta = k.phi;
            note += " -> start over";
        }
        record(k);
        prev = k;
    }

    double periodLo() {
        int n = Math.min(np, periods.length);
        double lo = 1;
        for (int i = 0; i < n; i++) lo = Math.min(lo, periods[i]);
        return n == 0 ? 0.05 : lo;
    }

    double periodHi() {
        int n = Math.min(np, periods.length);
        double hi = 0;
        for (int i = 0; i < n; i++) hi = Math.max(hi, periods[i]);
        return n == 0 ? 0.5 : hi;
    }

    boolean safeLabel(Plate pl, int L) {
        int c = pl.c[L], s = pl.s[L], n = c + s;
        if (n < 2) return false;
        if (c >= 2 && c * 5 >= n) return false;
        int cn = pl.cn[L], nn = cn + pl.sn[L];
        if (cn >= 1 && cn * 3 >= nn) return false;
        return true;
    }

    /** Plate p is safe to smash with the tower anywhere between th0 and th1 degrees. */
    boolean safe(int p, double th0, double th1) {
        if (p >= plates.size()) return false;
        Plate pl = plates.get(p);
        double a0 = 81 - margin - th1, a1 = 99 + margin - th0; // angles on the plate that pass the ball
        if (a1 - a0 >= 360) a1 = a0 + 359;
        for (long L = (long) Math.floor(a0 / 30); L <= (long) Math.floor(a1 / 30); L++)
            if (!safeLabel(pl, mod12(L))) return false;
        return true;
    }

    /**
     * Whether to hold for the frame the next touch will land in, given whether the finger is down
     * now (that decides the frames before it).
     */
    boolean wantHold(boolean down) {
        if (prev == null) return false;
        if (feverHolds()) return true;
        if (Double.isNaN(omega) || omegaVotes < 2) return false;
        double lo = periodLo(), hi = periodHi();
        // Frames before the touch lands: each smashes a plate while held (surely, if frames are
        // slower than the game's 75 ms between smashes; maybe, if faster).
        boolean certain = lo >= 0.08;
        int pMin = b, pMax = b;
        if (down) {
            pMax += delay - 1;
            if (certain) pMin += delay - 1;
        }
        double t0 = delay * gameTime(lo) * 0.95, t1 = delay * gameTime(hi) * 1.06;
        // The last six plates of a tower go in slow motion (62% speed): allow for either.
        if (prev.remaining > 0 && prev.remaining <= 8) t0 *= 0.6;
        double thA = theta + omega * t0, thB = theta + omega * t1;
        double th0 = Math.min(thA, thB), th1 = Math.max(thA, thB);
        for (int p = pMin; p <= pMax; p++)
            if (!safe(p, th0, th1)) return false;
        return true;
    }
}
