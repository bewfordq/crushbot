package com.crushbot.core;

/** A screenshot as packed 0xRRGGBB pixels. */
public final class Img implements Frame {
    public final int w, h;
    public final int[] px;
    public final long t;

    public Img(int w, int h, int[] px, long t) {
        this.w = w;
        this.h = h;
        this.px = px;
        this.t = t;
    }

    @Override public int w() { return w; }
    @Override public int h() { return h; }
    @Override public long t() { return t; }

    @Override public int rgb(int x, int y) {
        if (x < 0 || y < 0 || x >= w || y >= h) return 0;
        return px[y * w + x] & 0xffffff;
    }

    /** A copy of any frame (to keep a live capture for later). */
    public static Img of(Frame f) {
        if (f instanceof Img) return (Img) f;
        int w = f.w(), h = f.h();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) px[y * w + x] = 0xff000000 | f.rgb(x, y);
        return new Img(w, h, px, f.t());
    }

    /** Fraction of sampled pixels (every 7th) that differ, for "has the screen changed?". */
    public static double diff(Frame a, Frame b) {
        if (a == null || b == null || a.w() != b.w() || a.h() != b.h()) return 1;
        int n = 0, d = 0;
        for (int y = 0; y < a.h(); y += 7)
            for (int x = 0; x < a.w(); x += 7) {
                n++;
                if (Frame.diff(a.rgb(x, y), b.rgb(x, y)) > 40) d++;
            }
        return (double) d / n;
    }
}
