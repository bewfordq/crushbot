package com.crushbot.core;

import java.util.ArrayList;
import java.util.List;

/** The screens around the tower: buttons found by colour, places from the game's layout code. */
final class Screens {
    /** A button's box on screen. */
    static final class Box {
        final int x0, y0, x1, y1;

        Box(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        int cx() { return (x0 + x1) / 2; }
        int cy() { return (y0 + y1) / 2; }
        int w() { return x1 - x0; }
        int h() { return y1 - y0; }

        @Override public String toString() { return "[" + x0 + "," + y0 + " " + w() + "x" + h() + "]"; }
    }

    /** The game's call-to-action orange (f5a11c), a little lighter or darker when pressed. */
    static boolean orange(int c) {
        int r = Frame.r(c), g = Frame.g(c), b = Frame.b(c);
        return r >= 205 && g >= 120 && g <= 180 && b <= 70 && r - g >= 60;
    }

    /** Orange buttons: boxes of orange at least 150 x 70 px, found on a coarse grid. */
    static List<Box> orangeButtons(Frame f) {
        int st = 6, gw = f.w() / st, gh = f.h() / st;
        boolean[] m = new boolean[gw * gh];
        for (int y = 0; y < gh; y++)
            for (int x = 0; x < gw; x++) m[y * gw + x] = orange(f.rgb(x * st, y * st));
        boolean[] seen = new boolean[m.length];
        int[] stack = new int[m.length];
        List<Box> out = new ArrayList<>();
        for (int i = 0; i < m.length; i++) {
            if (!m[i] || seen[i]) continue;
            int sp = 0, n = 0, minX = gw, maxX = 0, minY = gh, maxY = 0;
            stack[sp++] = i;
            seen[i] = true;
            while (sp > 0) {
                int k = stack[--sp], x = k % gw, y = k / gw;
                n++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                int[] nb = {k - 1, k + 1, k - gw, k + gw};
                for (int j : nb) {
                    if (j < 0 || j >= m.length || seen[j] || !m[j]) continue;
                    if ((j == k - 1 && x == 0) || (j == k + 1 && x == gw - 1)) continue;
                    seen[j] = true;
                    stack[sp++] = j;
                }
            }
            Box bx = new Box(minX * st, minY * st, (maxX + 1) * st, (maxY + 1) * st);
            // Mostly orange (the white label takes some), button-sized.
            if (bx.w() < Eye.u(150) || bx.h() < Eye.u(70) || bx.h() > Eye.u(260)) continue;
            if (n * st * st < 0.45 * bx.w() * bx.h()) continue;
            out.add(bx);
        }
        return out;
    }

    /** The biggest orange button, the game's main way on ("Level 12" is at least 560 x 150). */
    static Box mainButton(List<Box> bs) {
        Box best = null;
        for (Box b : bs)
            if (b.w() >= Eye.u(480) && b.h() >= Eye.u(130) && (best == null || b.w() * b.h() > best.w() * best.h())) best = b;
        return best;
    }

    /**
     * The daily streak's "Stamp the royal seal" screen: a crown with three red jewels in a
     * circle, centred 24% of the way down the screen plus 230 units ((540, 812) on 1080 x 2424).
     * Tapping it stamps the seal.
     */
    static boolean streakSeal(Frame f) {
        int y = (int) Math.round(Eye.H * 0.24 + Eye.u(277));
        for (double dx : new double[] {-75, 0, 75})
            if (!Frame.near(f.rgb((int) Math.round(Eye.CX + Eye.u(dx)), y), 0xe05a4e, 22)) return false;
        return true;
    }

    static int[] seal() {
        return new int[] {(int) Eye.CX, (int) Math.round(Eye.H * 0.24 + Eye.u(230))};
    }

    /**
     * A broken streak: "Repair streak" (a video ad, orange, at 60% of the height) or
     * "Start fresh" 190 px below it. Returns where "Start fresh" is, or null.
     */
    static int[] startFresh(List<Box> bs) {
        double at = Eye.H * 0.6;
        for (Box b : bs)
            if (b.y0 > at - Eye.u(34) && b.y0 < at + Eye.u(36))
                return new int[] {(int) Eye.CX, (int) Math.round(b.y0 + Eye.u(240))};
        return null;
    }

    /** "Smashed by the curse!": white letters outlined in purple (7a3fd0), a little above the middle. */
    static boolean lostHeadline(Frame f) {
        int n = 0;
        double mid = Eye.H / 2.0;
        for (int y = (int) (mid - Eye.u(332)); y < mid + Eye.u(48); y += 3)
            for (int x = (int) (Eye.CX - Eye.u(390)); x < Eye.CX + Eye.u(390); x += 3)
                if (Frame.near(f.rgb(x, y), 0x7a3fd0, 28)) n++;
        return n >= 60 * Eye.S * Eye.S;
    }

    /** The screen darkened all over (the decree and song cards dim the game behind them). */
    static boolean darkVeil(Frame f) {
        int dark = 0, n = 0;
        for (int y = (int) (Eye.H * 0.165); y < Eye.H * 0.9; y += Math.max(20, (int) Eye.u(60)))
            for (int x : new int[] {20, 60, Eye.W - 60, Eye.W - 20}) {
                n++;
                if (Frame.lum(f.rgb(x, y)) < 90) dark++;
            }
        return dark >= n * 0.8;
    }

    /**
     * Where "Restart tower" is on the lost screen. The screen is a column centred on the screen
     * (40 units apart): the headline, the percentage, the video button if there is one, and the
     * restart pill (about 300 x 96). Without the video button its middle is 114 units below the
     * screen's middle.
     */
    static int[] restart(List<Box> bs) {
        double mid = Eye.H / 2.0;
        for (Box b : bs)
            if (b.cy() > mid - Eye.u(312) && b.cy() < mid + Eye.u(288))
                return new int[] {(int) Eye.CX, (int) Math.round(b.y1 + Eye.u(88))};
        return new int[] {(int) Eye.CX, (int) Math.round(mid + Eye.u(114))};
    }
}
