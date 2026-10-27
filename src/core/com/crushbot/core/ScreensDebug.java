package com.crushbot.core;

/** For the PC tools: what the screen code makes of a picture. */
public final class ScreensDebug {
    public static String describe(Img im) {
        java.util.List<Screens.Box> bs = Screens.orangeButtons(im);
        Eye.Look k = Eye.read(im);
        return (k.tower ? "tower" : k.pale ? "veiled" : "no tower") + " orange=" + bs + " main=" + Screens.mainButton(bs)
                + " dark=" + Screens.darkVeil(im) + " lostHeadline=" + Screens.lostHeadline(im) + " seal=" + Screens.streakSeal(im);
    }
}
