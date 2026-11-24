package com.example.democlient;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;

final class WatchSession {

    private final ConfigClient client;
    private final String configUrl;
    private final LongFunction<String> watchUrl;
    private final Timing timing;
    private final ConfigOutput output;

    private Long observedRevision;
    private Long confirmedCursor;
    private boolean pendingSync = true;
    private ConfigClient.FetchResult currentConfig;

    private Long recoveryStartedAt;
    private HttpRequestFailedException lastFailure;

    WatchSession(ConfigClient client, String configUrl, LongFunction<String> watchUrl,
                 Timing timing, ConfigOutput output) {
        this.client = client;
        this.configUrl = configUrl;
        this.watchUrl = watchUrl;
        this.timing = timing;
        this.output = output;
    }

    void run(int rounds) throws InterruptedException, JsonProcessingException {
        try {
            try {
                read("initial fetch");
            } catch (HttpRequestFailedException failure) {
                waitToRetry("initial fetch", failure);
            }

            System.out.println("\nWatching config changes (long polling) ...");
            for (int completed = 0; completed < rounds; completed++) {
                completeRound();
                System.out.println("Watch round " + (completed + 1) + "/" + rounds + " complete: "
                        + progress() + recoveryTime());
                recoveryStartedAt = null;
                lastFailure = null;
            }
            reportEnd("completed", null);
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            reportEnd("cancelled", cancelled);
            throw cancelled;
        } catch (RecoveryBudgetExhausted exhausted) {
            reportEnd("recovery-budget-exhausted", lastFailure);
            throw exhausted;
        } catch (RuntimeException failure) {
            reportEnd("terminal-error", failure);
            throw failure;
        }
    }

    private void completeRound() throws InterruptedException, JsonProcessingException {
        boolean observedThisRound = false;
        while (true) {
            try {
                if (!observedThisRound) {
                    beforeRequest();
                    long since = confirmedCursor == null ? 0 : confirmedCursor;
                    var observation = client.observeWatch(watchUrl.apply(since));
                    ReliableHttp.checkInterrupted();
                    observedRevision = observation.latestVersion();
                    observedThisRound = true;
                    pendingSync = confirmedCursor == null
                            || observation.changed() || observedRevision < confirmedCursor;
                    System.out.println("watch result: changed=" + observation.changed()
                            + ", latestVersion=" + observedRevision + ", " + progress());
                    if (!pendingSync) {
                        return;
                    }
                }

                beforeRequest();
                read("watch refresh");
                ReliableHttp.checkInterrupted();
                // The list carries no revision. This read can only confirm the earlier observation.
                confirmedCursor = observedRevision;
                pendingSync = false;
                return;
            } catch (HttpRequestFailedException failure) {
                waitToRetry(observedThisRound ? "watch refresh" : "watch poll", failure);
            }
        }
    }

    private void read(String label) throws InterruptedException, JsonProcessingException {
        var read = client.readConfigs(configUrl);
        ReliableHttp.checkInterrupted();
        currentConfig = read.result();
        output.print(label, currentConfig);
        if (read.fallbackFailure() != null) {
            System.out.println("Using stale configuration; waiting to sync.");
            throw read.fallbackFailure();
        }
    }

    private void beforeRequest() throws InterruptedException {
        ReliableHttp.checkInterrupted();
        if (recoveryStartedAt != null) {
            remainingNanos();
        }
    }

    private void waitToRetry(String operation, HttpRequestFailedException failure) throws InterruptedException {
        ReliableHttp.checkInterrupted();
        if (!failure.isRecoverable()) {
            throw failure;
        }

        if (recoveryStartedAt == null) {
            recoveryStartedAt = timing.nanoTime().getAsLong();
        }
        lastFailure = failure;
        System.out.println("WARN: Watch recovery pending (" + operation + "): " + progress()
                + recoveryTime() + ", failure=" + failure.getMessage());
        timing.sleeper().sleepNanos(Math.min(timing.retryIntervalNanos(), remainingNanos()));
    }

    private long remainingNanos() {
        long remaining = timing.timeoutNanos() - elapsedNanos();
        if (remaining <= 0) {
            throw new RecoveryBudgetExhausted(lastFailure);
        }
        return remaining;
    }

    private long elapsedNanos() {
        return timing.nanoTime().getAsLong() - recoveryStartedAt;
    }

    private String recoveryTime() {
        return recoveryStartedAt == null ? "" : ", recoveryElapsedMs=" + TimeUnit.NANOSECONDS.toMillis(elapsedNanos());
    }

    private String progress() {
        return "observedRevision=" + (observedRevision == null ? "unknown" : observedRevision)
                + ", confirmedCursor=" + (confirmedCursor == null ? "unconfirmed" : confirmedCursor)
                + ", pendingSync=" + pendingSync
                + ", source=" + (currentConfig == null ? "none" : currentConfig.source());
    }

    private void reportEnd(String reason, Throwable failure) {
        System.out.println("Watch ended: reason=" + reason + ", " + progress() + recoveryTime()
                + (failure == null ? "" : ", failure=" + failure.getMessage()));
    }

    record Timing(long timeoutNanos, long retryIntervalNanos, LongSupplier nanoTime, Sleeper sleeper) {
        Timing {
            if (timeoutNanos <= 0 || retryIntervalNanos <= 0) {
                throw new IllegalArgumentException("Watch recovery timeout and retry interval must be positive");
            }
        }

        static Timing fromSettings(long timeoutSeconds, long intervalMs) {
            try {
                return new Timing(Math.multiplyExact(timeoutSeconds, 1_000_000_000L),
                        Math.multiplyExact(intervalMs, 1_000_000L), System::nanoTime, TimeUnit.NANOSECONDS::sleep);
            } catch (ArithmeticException cause) {
                throw new IllegalArgumentException("Watch recovery timeout or retry interval exceeds nanosecond range", cause);
            }
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    @FunctionalInterface
    interface ConfigOutput {
        void print(String label, ConfigClient.FetchResult result) throws JsonProcessingException;
    }

    private static final class RecoveryBudgetExhausted extends RuntimeException {
        RecoveryBudgetExhausted(HttpRequestFailedException lastFailure) {
            super("Watch recovery budget exhausted", lastFailure);
        }
    }
}
