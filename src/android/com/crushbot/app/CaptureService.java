package com.crushbot.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.WindowManager;

import com.crushbot.core.Frame;

import java.nio.ByteBuffer;
import java.util.function.Function;

/**
 * Live screen capture (MediaProjection): every frame the screen shows, a frame or so later. The
 * bot reads pixels straight out of the newest frame's buffer, without copying it.
 */
public final class CaptureService extends Service {
    static volatile CaptureService instance;

    MediaProjection projection;
    VirtualDisplay display;
    ImageReader reader;
    HandlerThread thread;
    final Object lock = new Object();
    Image held;
    long heldT;
    int frames;
    int w, h, fullW, fullH;
    float scale = 1;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("capture", "Screen capture", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "capture")
                .setContentTitle("Crushbot is watching the game")
                .setContentText("Screen capture is on while the bot plays.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data", Intent.class);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = data == null ? null : mpm.getMediaProjection(code, data);
        if (projection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        thread = new HandlerThread("capture", android.os.Process.THREAD_PRIORITY_DISPLAY);
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                stopSelf();
            }
        }, handler);
        Rect b = getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        // A smaller copy of the screen: the emulator pays for every byte it copies out of its GPU.
        scale = getSharedPreferences("crushbot", MODE_PRIVATE).getFloat("capScale", 0.5f);
        fullW = b.width();
        fullH = b.height();
        w = Math.round(fullW * scale);
        h = Math.round(fullH * scale);
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(r -> {
            Image img;
            try {
                img = r.acquireLatestImage();
            } catch (IllegalStateException e) {
                return; // all buffers held; the next frame will do
            }
            if (img == null) return;
            synchronized (lock) {
                if (held != null) held.close();
                held = img;
                heldT = img.getTimestamp();
                frames++;
                lock.notifyAll();
            }
        }, handler);
        display = projection.createVirtualDisplay("crushbot", w, h, getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
        instance = this;
        return START_NOT_STICKY;
    }

    /**
     * A view of the held image in full-screen coordinates (each read goes to the nearest pixel of
     * the smaller copy). Only valid inside {@link #look}.
     */
    static final class View implements Frame {
        ByteBuffer buf;
        int stride, w, h, iw, ih;
        float scale;
        long t;

        @Override public int w() { return w; }
        @Override public int h() { return h; }
        @Override public long t() { return t; }

        @Override public int rgb(int x, int y) {
            int sx = (int) (x * scale), sy = (int) (y * scale);
            if (sx < 0 || sy < 0 || sx >= iw || sy >= ih) return 0;
            return buf.getInt(sy * stride + sx * 4) >>> 8; // bytes R,G,B,A read big-endian
        }
    }

    final View view = new View();

    /**
     * Waits (up to timeoutMs) for a frame newer than afterT, then runs fn on it while it's held.
     * Null on timeout.
     */
    <T> T look(long afterT, long timeoutMs, Function<Frame, T> fn) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        synchronized (lock) {
            while (held == null || heldT <= afterT) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) return null;
                lock.wait(left);
            }
            Image.Plane plane = held.getPlanes()[0];
            view.buf = plane.getBuffer().order(java.nio.ByteOrder.BIG_ENDIAN); // bytes R,G,B,A
            view.stride = plane.getRowStride();
            view.iw = held.getWidth();
            view.ih = held.getHeight();
            view.scale = scale;
            view.w = fullW;
            view.h = fullH;
            view.t = heldT;
            T out = fn.apply(view);
            if (keepCopies) {
                // A raw copy of the frame just read (a quick bulk copy), to save it if it turns
                // out to be interesting.
                ByteBuffer b = view.buf.duplicate();
                b.clear();
                if (copy == null || copy.length != b.remaining()) copy = new byte[b.remaining()];
                b.get(copy);
                copyStride = view.stride;
                copyW = view.iw;
                copyH = view.ih;
            }
            return out;
        }
    }

    volatile boolean keepCopies;
    byte[] copy;
    int copyStride, copyW, copyH;

    /** The last frame read, as a picture (at capture size). */
    com.crushbot.core.Img lastCopy() {
        synchronized (lock) {
            if (copy == null) return null;
            int[] px = new int[copyW * copyH];
            for (int y = 0; y < copyH; y++)
                for (int x = 0; x < copyW; x++) {
                    int o = y * copyStride + x * 4;
                    px[y * copyW + x] = 0xff000000 | (copy[o] & 255) << 16 | (copy[o + 1] & 255) << 8 | (copy[o + 2] & 255);
                }
            return new com.crushbot.core.Img(copyW, copyH, px, 0);
        }
    }

    static void stop(android.content.Context c) {
        c.stopService(new Intent(c, CaptureService.class));
    }

    @Override public void onDestroy() {
        instance = null;
        if (display != null) display.release();
        if (projection != null) projection.stop();
        synchronized (lock) {
            if (held != null) held.close();
            held = null;
        }
        if (reader != null) reader.close();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }
}
