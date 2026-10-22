package com.aide.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ScreenWorker extends Worker {

    public ScreenWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Usage.check(getApplicationContext());
        } catch (Exception ignored) {
        }
        return Result.success();
    }
}
