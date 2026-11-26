package com.localcopy.terminal;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Bounded concurrent exchange reads. Only the follow worker consumes results or submits orders. */
final class CopyChecks implements AutoCloseable {
    private static final int LANES = 4;

    private static final class Job {
        final CopyPlan.Request request;
        final Future<CopyPlan> result;

        Job(CopyPlan.Request request, Future<CopyPlan> result) {
            this.request = request;
            this.result = result;
        }
    }

    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(LANES, LANES,
            60, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                Thread thread = new Thread(task, "copy-adjust-read");
                thread.setDaemon(true);
                return thread;
            });
    private final ThreadLocal<FollowExecutor.Exchange> readers;
    private final Runnable onReady;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private int nextIndex;

    CopyChecks(Supplier<FollowExecutor.Exchange> reader, Runnable onReady) {
        readers = ThreadLocal.withInitial(reader);
        this.onReady = onReady;
        workers.allowCoreThreadTimeOut(true);
    }

    void discardExcept(Map<String, String> revisions) {
        jobs.values().removeIf(job -> {
            if (job.request.revision.equals(revisions.get(job.request.key))) return false;
            job.result.cancel(true);
            return true;
        });
    }

    void invalidate(String symbol) {
        jobs.values().removeIf(job -> {
            if (!symbol.equals(job.request.symbol)) return false;
            job.result.cancel(true);
            return true;
        });
    }

    CopyPlan take(CopyPlan.Request request) throws Exception {
        Job job = jobs.get(request.key);
        if (job == null || !job.result.isDone()) return null;
        jobs.remove(request.key);
        try { return job.result.get(); }
        catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw (Error) cause;
        }
    }

    void schedule(List<CopyPlan.Request> requests, Set<String> attempted, boolean increase, BigDecimal feeBuffer) {
        int size = requests.size();
        if (size == 0) return;
        int start = nextIndex % size;
        for (int i = 0; i < size; i++) {
            int index = (start + i) % size;
            CopyPlan.Request request = requests.get(index);
            if (attempted.contains(request.key) || jobs.containsKey(request.key)) continue;
            try {
                FutureTask<CopyPlan> result = new FutureTask<CopyPlan>(
                        () -> CopyPlan.prepare(request, readers.get(), increase, feeBuffer)) {
                    @Override protected void done() {
                        if (!isCancelled()) onReady.run();
                    }
                };
                workers.execute(result);
                jobs.put(request.key, new Job(request, result));
                nextIndex = (index + 1) % size;
            } catch (RejectedExecutionException full) {
                nextIndex = index;
                return;
            }
        }
    }

    // Service shutdown can interrupt the worker without accessing its private job map.
    @Override public void close() { workers.shutdownNow(); }
}
