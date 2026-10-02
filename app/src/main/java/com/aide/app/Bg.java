package com.aide.app;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Bg {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bg() {}

    static void run(Runnable r) {
        POOL.execute(r);
    }

    static void main(Runnable r) {
        MAIN.post(r);
    }
}
