package com.aide.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class QueueWorker extends Worker {

    public QueueWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean more;
        synchronized (Queue.LOCK) {
            more = Queue.process(getApplicationContext());
        }
        return more ? Result.retry() : Result.success();
    }
}
