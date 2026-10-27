package com.crushbot.desktop;

import com.crushbot.core.Eye;
import com.crushbot.core.Img;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** PC-side tools: read screenshots with the bot's eyes. */
public final class Dev {
    public static Img load(File f) throws Exception {
        BufferedImage bi = ImageIO.read(f);
        int w = bi.getWidth(), h = bi.getHeight();
        int[] px = bi.getRGB(0, 0, w, h, null, 0, w);
        if (w >= 1080) return new Img(w, h, px, 0);
        // A frame the bot kept at capture size: read it as the bot did, nearest pixel.
        double sc = w / 1080.0;
        int W = 1080, H = (int) Math.round(h / sc);
        int[] big = new int[W * H];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) big[y * W + x] = px[Math.min(h - 1, (int) (y * sc)) * w + Math.min(w - 1, (int) (x * sc))];
        return new Img(W, H, big, 0);
    }

    public static void main(String[] a) throws Exception {
        String cmd = a[0];
        if (cmd.equals("screen")) {
            for (int i = 1; i < a.length; i++) {
                Img im = load(new File(a[i]));
                System.out.println(new File(a[i]).getName() + ": " + com.crushbot.core.ScreensDebug.describe(im));
            }
        }
        if (cmd.equals("look")) {
            Eye.DEBUG = System.getenv("DEBUG") != null;
            for (int i = 1; i < a.length; i++) {
                Img im = load(new File(a[i]));
                long t0 = System.nanoTime();
                Eye.Look k = Eye.read(im);
                long us = (System.nanoTime() - t0) / 1000;
                System.out.println(new File(a[i]).getName() + " (" + us + " us): " + k.brief());
            }
        }
    }
}
