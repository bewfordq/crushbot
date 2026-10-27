package com.crushbot.core;

/** A screen picture the bot can sample: a live capture buffer, a screenshot or a PNG. */
public interface Frame {
    int w();
    int h();

    /** 0xRRGGBB at (x, y), or 0 outside the picture. */
    int rgb(int x, int y);

    /** When the picture was on screen, System.nanoTime() clock (0 if unknown). */
    long t();

    static int r(int c) { return (c >> 16) & 255; }
    static int g(int c) { return (c >> 8) & 255; }
    static int b(int c) { return c & 255; }
    static int lum(int c) { return (r(c) * 3 + g(c) * 6 + b(c)) / 10; }
    static int max(int c) { return Math.max(r(c), Math.max(g(c), b(c))); }
    static int min(int c) { return Math.min(r(c), Math.min(g(c), b(c))); }
    static int sat(int c) { return max(c) - min(c); }

    static int diff(int p, int q) {
        return Math.abs(r(p) - r(q)) + Math.abs(g(p) - g(q)) + Math.abs(b(p) - b(q));
    }

    static boolean near(int p, int q, int tol) {
        return Math.abs(r(p) - r(q)) <= tol && Math.abs(g(p) - g(q)) <= tol && Math.abs(b(p) - b(q)) <= tol;
    }
}
