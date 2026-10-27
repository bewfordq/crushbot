package com.crushbot.core;

import java.util.Arrays;

/**
 * Reads the tower off one frame. The geometry is the game's own (TowerView.gd): a cylinder of
 * plates 68 units tall, radius 400, squashed to 120 units deep, centred across the screen. Each
 * plate has 12 segments of 30 degrees; world angle a is drawn at x = centre + 400 cos a, so the
 * front half (0..180 degrees) is what shows, 90 degrees in the middle. The ball checks the
 * segments at 81, 90 and 99 degrees of the plate on top.
 *
 * The game draws on a 1080 x 1920 canvas stretched to fit the screen and grown to fill it
 * (Godot's canvas_items / expand): one canvas unit is S screen pixels, S = min(width / 1080,
 * height / 1920). On a 1080 x 2424 screen that's 1:1.
 */
public final class Eye {
    public static int W = 1080, H = 2424;
    public static double S = 1, CX = 540, RX = 400, RY = 120, PH = 68;
    /**
     * Cap centre once the camera has caught up: 650 canvas units plus the phone's safe-area top
     * (its status bar or camera cut-out), so it differs between phones and is measured at the
     * start of a level (792 on a 1080 x 2424 emulator).
     */
    public static double TOP_Y = 792;
    public static boolean topKnown = false;
    /** Lowest y worth reading (the crowd of little figures is below). */
    public static int BOTTOM = 2290;

    /** Sets the geometry for a screen of w x h pixels. */
    public static void screen(int w, int h) {
        if (w == W && h == H && S > 0) return;
        W = w;
        H = h;
        S = Math.min(w / 1080.0, h / 1920.0);
        CX = w / 2.0;
        RX = 400 * S;
        RY = 120 * S;
        PH = 68 * S;
        BOTTOM = (int) Math.round(h - 134 * S);
        TOP_Y = 792 * S;
        topKnown = false;
    }

    /** The cap's resting height, once measured. */
    public static void top(double y) {
        TOP_Y = y;
        topKnown = true;
    }

    /** Canvas units to screen pixels. */
    static double u(double v) {
        return v * S;
    }
    public static final int SEGS = 7; // r = -1..5: segments starting at phi + 30 r
    static final double D = Math.PI / 180;

    public static final byte UNKNOWN = 0, SAFE = 1, CURSED = 2;

    /** What a frame shows. */
    public static final class Look {
        public long t;
        public boolean tower;   // a centred tower with its top cap, in play
        public boolean pale;    // a tower behind a light veil (the won / lost screens)
        public boolean shifted; // a tower off centre: a new stage sliding in
        public double y0;       // cap centre y
        public int cap;         // cap colour
        public int capCols;     // columns that agreed on y0
        public boolean atRest;  // y0 is one of the camera's resting places (TOP_Y, TOP_Y + 68)
        public double phi = -1; // segment boundary phase, degrees in [0, 30); -1 unknown
        public double phiQ;     // stripe strength at phi over the runner-up
        public int plates;      // plate bands read: j = 0 .. plates - 1
        public byte[][] seg;    // [j][r + 1]: segment starting at phi + 30 r
        public double fever = -1; // the fever bar under the progress bar, 0..1 (-1: can't see it)
        public int remaining = -1; // plates left (top one included), when the tower's gold base shows
        public long sum;        // checksum of the tower area: the same twice means no new game frame
        public int parity = -1; // parity of the top plate's index (odd plates are drawn 12% darker)
        public double parityQ;

        public String brief() {
            if (!tower) return pale ? "veiled tower" : "no tower";
            StringBuilder sb = new StringBuilder(String.format("y0=%.1f phi=%.2f (q%.1f) par=%d (%.1f) %s",
                    y0, phi, phiQ, parity, parityQ, remaining > 0 ? "left=" + remaining + " " : "")
                    + (fever >= 0 ? String.format("fev=%.2f ", fever) : ""));
            if (seg != null)
                for (int j = 0; j < Math.min(plates, 12); j++) {
                    for (byte b : seg[j]) sb.append(b == CURSED ? '#' : b == SAFE ? '.' : '?');
                    sb.append(' ');
                }
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ cap

    static final int[] CAP_DEG = {30, 40, 52, 64, 76, 104, 116, 128, 140, 150};

    public static boolean DEBUG = false;

    /**
     * Finds the top cap (a flat ellipse of one colour) and its centre. The colour isn't fixed: a
     * smash flashes the whole screen a little, and a gold plate on top has a gold cap.
     */
    static boolean cap(Frame f, Look k) {
        // Where to look: around the resting place once it's known, a wide band until then.
        int CAP_Y0, CAP_Y1;
        if (topKnown) {
            CAP_Y0 = (int) (TOP_Y - RY - u(60));
            CAP_Y1 = (int) (TOP_Y + 2 * PH + RY + u(60));
        } else {
            CAP_Y0 = (int) (H * 0.12);
            CAP_Y1 = (int) (H * 0.62);
        }
        CAP_Y0 = Math.max(0, CAP_Y0);
        CAP_Y1 = Math.min(H, CAP_Y1);
        // The cap colour: the commonest colour in two columns either side (a face band is at most
        // 68 units of one colour there; the cap is 200).
        int[] cols = new int[2 * Math.max(1, CAP_Y1 - CAP_Y0)];
        int nc = 0;
        for (int deg : new int[] {64, 116}) {
            int x = (int) Math.round(CX + RX * Math.cos(deg * D));
            for (int y = CAP_Y0; y < CAP_Y1; y++) {
                int c = f.rgb(x, y);
                if (Frame.lum(c) >= 90) cols[nc++] = c;
            }
        }
        Arrays.sort(cols, 0, nc);
        int col = 0, best = 0;
        for (int i = 0; i < nc; ) {
            int j = i;
            while (j < nc && cols[j] == cols[i]) j++;
            if (j - i > best) {
                best = j - i;
                col = cols[i];
            }
            i = j;
        }
        if (best < u(150)) return false;
        k.cap = col;
        // Each column: the cap's top and bottom edges, half a chord either side of the centre.
        // Gaps of a few pixels (impact rings, sparks) are bridged.
        double[] ys = new double[CAP_DEG.length * 2];
        int[] colOf = new int[ys.length];
        int n = 0;
        for (int ci = 0; ci < CAP_DEG.length; ci++) {
            int deg = CAP_DEG[ci];
            double a = deg * D;
            int x = (int) Math.round(CX + RX * Math.cos(a));
            double half = RY * Math.sin(a);
            int bestTop = -1, bestBot = -1, bestIn = 0;
            int top = -1, last = -100, in = 0;
            for (int y = CAP_Y0; y < CAP_Y1; y++) {
                if (!Frame.near(f.rgb(x, y), col, 4)) continue;
                if (y - last > u(14)) {
                    top = y;
                    in = 0;
                }
                in++;
                last = y;
                if (in > bestIn) {
                    bestIn = in;
                    bestTop = top;
                    bestBot = y;
                }
            }
            if (DEBUG) System.out.printf("  cap %d: in=%d top=%d bot=%d half=%.0f -> %.1f %.1f%n", deg, bestIn, bestTop, bestBot, half, bestTop + half, bestBot + 1 - half);
            if (bestIn < u(24)) continue;
            // Both ends give a centre; often one is hidden (flying pieces of the last plate
            // cover the back edge), so the columns vote below.
            ys[n] = bestBot + 1 - half;
            colOf[n++] = ci;
            ys[n] = bestTop + half;
            colOf[n++] = ci;
        }
        // The centre most columns agree on (within 3 px). Big combos send pieces of the last
        // plate flying over one side of the cap, so one side alone will do. While the game runs
        // under 26 frames a second the camera catches up within the frame: the cap rests at
        // TOP_Y, or one plate lower right after a smash; those win if any 2 columns say so.
        double tol = Math.max(2, u(4));
        for (double rest : topKnown ? new double[] {TOP_Y, TOP_Y + PH} : new double[0]) {
            int used = 0, agree = 0;
            double sum = 0;
            for (int j = 0; j < n; j++)
                if (Math.abs(ys[j] - rest) <= tol) {
                    agree++;
                    sum += ys[j];
                    used |= 1 << colOf[j];
                }
            if (Integer.bitCount(used) >= 2 && Integer.bitCount(used) * 2 >= Math.min(4, CAP_DEG.length)) {
                k.y0 = sum / agree;
                k.capCols = Integer.bitCount(used);
                k.atRest = true;
                return true;
            }
        }
        int bestAgree = 0, bestCols = 0;
        double bestSum = 0;
        for (int i = 0; i < n; i++) {
            int agree = 0, used = 0;
            double sum = 0;
            for (int j = 0; j < n; j++)
                if (Math.abs(ys[j] - ys[i]) <= Math.max(2, u(3))) {
                    agree++;
                    sum += ys[j];
                    used |= 1 << colOf[j];
                }
            int nCols = Integer.bitCount(used);
            if (nCols > bestCols || (nCols == bestCols && agree > bestAgree)) {
                bestCols = nCols;
                bestAgree = agree;
                bestSum = sum;
            }
        }
        if (bestCols < 3) return false;
        k.y0 = bestSum / bestAgree;
        k.capCols = bestAgree;
        return k.y0 > H * 0.15 && k.y0 < H * 0.6;
    }

    // ------------------------------------------------------------------ stripes

    static final double A0 = 18, A1 = 162, STEP = 0.25;
    static final int NA = (int) Math.round((A1 - A0) / STEP) + 1;
    static final double[] COS = new double[NA], SIN = new double[NA];

    static {
        for (int i = 0; i < NA; i++) {
            COS[i] = Math.cos((A0 + i * STEP) * D);
            SIN[i] = Math.sin((A0 + i * STEP) * D);
        }
    }

    /** Colours along the middle of plate band j, at A0 + i * STEP degrees. */
    static int[] band(Frame f, double y0, int j, double off) {
        int[] c = new int[NA];
        for (int i = 0; i < NA; i++) {
            int x = (int) Math.round(CX + RX * COS[i]);
            int y = (int) Math.round(y0 + PH * j + off + RY * SIN[i]);
            c[i] = f.rgb(x, y);
        }
        return c;
    }

    /**
     * The segments' phase: every 30 degrees the colour steps (the two palette shades alternate,
     * and there's a faint seam), so the phase is where the summed colour steps line up best.
     */
    static void phase(Frame f, Look k, int jmax) {
        double[] edge = new double[NA - 1];
        for (int j = jmax >= 4 ? 1 : 0; j <= jmax; j++) {
            int[] c = band(f, k.y0, j, PH / 2);
            for (int i = 0; i + 1 < NA; i++) edge[i] += Math.min(120, Frame.diff(c[i], c[i + 1]));
        }
        int per = (int) Math.round(30 / STEP); // 120 samples per segment
        double[] score = new double[per];
        int[] cnt = new int[per];
        for (int i = 0; i + 1 < NA; i++) {
            // An edge between samples i and i+1 is at A0 + (i + 0.5) STEP degrees.
            int ph = i % per;
            score[ph] += edge[i];
            cnt[ph]++;
        }
        int bi = 0;
        for (int p = 0; p < per; p++) {
            score[p] /= Math.max(1, cnt[p]);
            if (score[p] > score[bi]) bi = p;
        }
        // Runner-up away from the peak (the in-segment shading steps sit at +-10 degrees).
        double second = 0;
        for (int p = 0; p < per; p++) {
            int dist = Math.min(Math.abs(p - bi), per - Math.abs(p - bi));
            if (dist > 4) second = Math.max(second, score[p]);
        }
        double deg = A0 + (bi + 0.5) * STEP;
        k.phi = ((deg % 30) + 30) % 30;
        k.phiQ = score[bi] / Math.max(1, second);
    }

    // ------------------------------------------------------------------ segments

    static final int[] ROWS = {12, 56}; // inside a band (canvas units), clear of the cursed stones' glowing dots

    /** A pixel on a plate face: cursed stone, ordinary (or gold, armour) segment, or can't tell. */
    static byte kind(int c) {
        int m = Frame.max(c);
        if (m < 62) return CURSED;
        if (m >= 76) return SAFE;
        return UNKNOWN;
    }

    static void segments(Frame f, Look k, int jmax) {
        k.plates = jmax + 1;
        k.seg = new byte[k.plates][SEGS];
        for (int j = 0; j <= jmax; j++) {
            for (int r = -1; r <= 5; r++) {
                double start = k.phi + 30 * r;
                int cursed = 0, safe = 0;
                for (int s = 4; s <= 26; s += 5) {
                    double a = start + s;
                    if (a < 9 || a > 171) continue;
                    double ar = a * D;
                    int x = (int) Math.round(CX + RX * Math.cos(ar));
                    double top = k.y0 + PH * j + RY * Math.sin(ar);
                    for (int row : ROWS) {
                        int y = (int) Math.round(top + u(row));
                        if (y >= BOTTOM) continue;
                        byte b = kind(f.rgb(x, y));
                        if (b == CURSED) cursed++;
                        else if (b == SAFE) safe++;
                    }
                }
                byte v = UNKNOWN;
                if (cursed >= 2 && cursed * 2 >= safe) v = CURSED;
                else if (safe >= 3 && cursed == 0) v = SAFE;
                k.seg[j][r + 1] = v;
            }
        }
    }

    /**
     * Odd plates are drawn 12% darker (and each plate down 2.5% darker): comparing neighbouring
     * plates at the same angles tells whether the top plate is odd or even.
     */
    static void parity(Frame f, Look k, int jmax) {
        double e = 0;
        int votes = 0;
        for (int j = 0; j < Math.min(jmax, 8); j++) {
            double[] rs = new double[64];
            int n = 0;
            for (int deg = 24; deg <= 156 && n < rs.length; deg += 6) {
                double ar = deg * D;
                // Skip near the segment edges.
                double inSeg = ((deg - k.phi) % 30 + 30) % 30;
                if (inSeg < 4 || inSeg > 26) continue;
                int x = (int) Math.round(CX + RX * Math.cos(ar));
                double top = k.y0 + PH * j + RY * Math.sin(ar);
                int p = f.rgb(x, (int) Math.round(top + u(20)));
                int q = f.rgb(x, (int) Math.round(top + PH + u(20)));
                if (kind(p) != SAFE || kind(q) != SAFE) continue;
                double rr = (Frame.r(q) + 1.0) / (Frame.r(p) + 1), rg = (Frame.g(q) + 1.0) / (Frame.g(p) + 1),
                        rb = (Frame.b(q) + 1.0) / (Frame.b(p) + 1);
                // The same colour darkened: all three channels scale alike.
                if (Math.abs(rr - rg) > 0.06 || Math.abs(rg - rb) > 0.06) continue;
                rs[n++] = (rr + rg + rb) / 3;
            }
            if (n < 3) continue;
            Arrays.sort(rs, 0, n);
            double ratio = rs[n / 2];
            double v = ratio < 0.94 ? 1 : ratio > 1.03 ? -1 : 0; // +1: plate j even (j+1 darker)
            e += (j % 2 == 0) ? v : -v;
            if (v != 0) votes++;
        }
        if (votes >= 2 && Math.abs(e) >= 2) {
            k.parity = e > 0 ? 0 : 1;
            k.parityQ = Math.abs(e);
        }
    }

    /**
     * In play the plate faces are strong colours; the won and lost screens lay a light veil over
     * the tower (and the decree cards a dark one, which hides the cap).
     */
    static boolean pale(Frame f, double y0, int plates) {
        int[] sats = new int[24], lums = new int[24];
        int n = 0;
        for (int j = plates >= 5 ? 1 : 0; j <= Math.min(4, plates - 1); j++)
            for (int deg = 45; deg <= 135; deg += 18) {
                double ar = deg * D;
                int c = f.rgb((int) Math.round(CX + RX * Math.cos(ar)), (int) Math.round(y0 + PH * j + RY * Math.sin(ar) + u(20)));
                if (Frame.max(c) < 62) continue; // cursed stone
                sats[n] = Frame.sat(c);
                lums[n++] = Frame.lum(c);
            }
        if (n < 4) return false;
        Arrays.sort(sats, 0, n);
        Arrays.sort(lums, 0, n);
        // (Darkened faces lose saturation too, so pale means light as well as grey.)
        return lums[n / 2] > 185 && sats[n / 2] < 50;
    }

    /**
     * The fever bar: orange (ff7a1f), 14 units tall, 437 units above the cap's resting centre
     * (both sit below the safe-area top), from 140 units in from the left edge to 140 from the
     * right. A full bar starts the fever: for a while cursed stones don't count.
     */
    static double fever(Frame f) {
        // Score pop-ups and flying coins cross the bar: take the furthest point up to which at
        // least 70% of it is orange, in any of three rows.
        double x0 = u(140), x1 = W - u(140);
        int yc = (int) Math.round(TOP_Y - u(437)), dy = (int) Math.max(1, Math.round(u(4)));
        int step = (int) Math.max(2, Math.round(u(4)));
        int n = 0, orange = 0, end = -1;
        for (int x = (int) x0 + 2; x <= x1 - 2; x += step) {
            n++;
            boolean o = false;
            for (int y = yc - dy; y <= yc + dy && !o; y += dy) o = Frame.near(f.rgb(x, y), 0xff7a1f, 40);
            if (o) {
                orange++;
                if (orange >= 0.7 * n) end = x;
            }
        }
        if (end < 0) return Frame.near(f.rgb((int) x0 + 1, yc), 0xff7a1f, 60) || n == 0 ? 0 : -1;
        return Math.min(1, (end + 2 - x0) / (x1 - x0));
    }

    /**
     * A new stage slides in from the left over a few frames: its whole cap sits left of where it
     * belongs, spilling past the tower's usual left edge (x 140) and short of its right edge
     * (x 940).
     */
    static boolean shifted(Frame f, Look k) {
        int y = (int) Math.round(k.y0);
        int spill = 0, right = 0;
        for (int i = -2; i <= 2; i++) {
            int yy = (int) Math.round(y + i * u(12));
            for (double dx : new double[] {-432, -418})
                if (Frame.near(f.rgb((int) Math.round(CX + u(dx)), yy), k.cap, 4)) spill++;
            for (double dx : new double[] {380, 392})
                if (Frame.near(f.rgb((int) Math.round(CX + u(dx)), yy), k.cap, 6)) right++;
        }
        return spill >= 6 && right == 0;
    }

    static boolean gold(int c) {
        return Frame.near(c, 0xe0a721, 30) || Frame.near(c, 0xffd23f, 30);
    }

    /**
     * Near the end the tower's foot shows: a gold disc (radius 448, wider than the plates) under
     * the last plate, on a pole. Its top edge at x = 108 and 972 is 25 px above the bottom of the
     * last plate. Returns the plates left, or -1 if the foot is off screen.
     */
    static int remaining(Frame f, double y0) {
        int[] top = new int[2];
        int[] xs = {(int) Math.round(CX - u(432)), (int) Math.round(CX + u(432))};
        int d2 = (int) Math.max(1, Math.round(u(2)));
        for (int i = 0; i < 2; i++) {
            top[i] = -1;
            for (int y = (int) (y0 + u(40)); y < f.h() - 2 * d2; y++)
                if (gold(f.rgb(xs[i], y)) && gold(f.rgb(xs[i], y + d2)) && gold(f.rgb(xs[i], y + 2 * d2))) {
                    top[i] = y;
                    break;
                }
        }
        if (top[0] < 0 || top[1] < 0 || Math.abs(top[0] - top[1]) > u(8)) return -1;
        double endY = (top[0] + top[1]) / 2.0 + u(25.5);
        return (int) Math.max(1, Math.round((endY - y0) / PH));
    }

    /**
     * The screen is also copied when something else changes (a notification sliding in), so the
     * same game frame can arrive twice: a checksum of the tower area tells.
     */
    static long checksum(Frame f) {
        long h = 1125899906842597L;
        int dy = (int) Math.max(8, u(46)), dx = (int) Math.max(8, u(56));
        for (int y = (int) (TOP_Y - u(152)); y < H * 0.82; y += dy)
            for (int x = (int) (CX - u(390)); x < CX + u(400); x += dx) h = 31 * h + f.rgb(x, y);
        return h;
    }

    public static Look read(Frame f) {
        screen(f.w(), f.h());
        Look k = new Look();
        k.t = f.t();
        k.sum = checksum(f);
        if (!cap(f, k)) return k;
        k.remaining = remaining(f, k.y0);
        k.fever = fever(f);
        if (pale(f, k.y0, k.remaining < 0 ? 99 : k.remaining)) {
            k.pale = true;
            return k;
        }
        if (shifted(f, k)) {
            k.shifted = true;
            return k;
        }
        k.tower = true;
        int jmax = 0;
        while (k.y0 + PH * (jmax + 1) + RY + PH < BOTTOM) jmax++;
        jmax = Math.min(jmax, 20);
        if (k.remaining > 0) jmax = Math.min(jmax, k.remaining - 1);
        phase(f, k, Math.max(Math.min(jmax, 12), 1));
        segments(f, k, jmax);
        parity(f, k, jmax);
        return k;
    }
}
