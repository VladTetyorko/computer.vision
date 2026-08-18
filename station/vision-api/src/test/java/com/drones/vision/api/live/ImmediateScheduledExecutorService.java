package com.drones.vision.api.live;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Shared test double: runs every submitted/scheduled task synchronously, on the calling thread, the
 * moment it's submitted. Makes {@link LiveUpdateRegistry#publishFleetChanged()}/{@link
 * LiveUpdateRegistry#publishEvent}/{@link LiveUpdateRegistry#publishDetectionEvent} deterministic in
 * a pure unit test with no real waiting, and makes the constructor's own {@code
 * scheduleAtFixedRate} calls (the periodic flush/heartbeat) a harmless no-op (this fake never
 * actually re-invokes a periodic task on its own). Package-private and shared across every {@code
 * com.drones.vision.api.live} test that needs a {@link LiveUpdateRegistry} instance (originally
 * private to {@link LiveUpdateRegistryTest}; extracted for {@link LiveUpdateStatusProviderTest} to
 * reuse rather than re-implement the same fifteen-method interface).
 */
final class ImmediateScheduledExecutorService implements ScheduledExecutorService {
    @Override
    public void execute(Runnable command) {
        command.run();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        return null; // never actually re-ticks -- tests call flushPending()/heartbeatAll() directly
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return null;
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        return null;
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        return null;
    }

    @Override
    public void shutdown() {
    }

    @Override
    public List<Runnable> shutdownNow() {
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Future<?> submit(Runnable task) {
        task.run();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException();
    }
}
