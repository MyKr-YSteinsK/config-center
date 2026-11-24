package com.example.democlient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(OutputCaptureExtension.class)
class WatchCliTest {
    private static final String BASE_URL = "http://127.0.0.1:8080";
    private static final String CONFIG_URL = BASE_URL + "/api/configs?app=app&env=dev";
    private static final String OLD = body("old");
    private static final String NEW = body("new");
    private static final String EMPTY = "{\"code\":0,\"data\":[]}";

    @Test
    void oneWriteIsAppliedAfterRefreshFailureBeforeNextWatch(CapturedOutput output) throws Exception {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(1);
        cli.watch(true, 2);
        cli.list(offline());
        cli.list(() -> { throw HttpRequestFailedException.circuitOpen("state=OPEN"); });
        cli.list(config("new", NEW));
        cli.watch(false, 2);

        cli.run(3);

        assertThat(cli.cursors).containsExactly(0L, 1L, 2L);
        assertThat(cli.configTimes).containsExactly(0L, 0L, 0L, 300L, 600L);
        assertThat(cli.cached().body).isEqualTo(NEW);
        assertThat(output).containsSubsequence(
                "Using stale configuration", "observedRevision=2, confirmedCursor=1, pendingSync=true",
                "CIRCUIT_OPEN", "Watch round 2/3 complete: observedRevision=2, confirmedCursor=2, pendingSync=false",
                "Watch round 3/3 complete", "cacheHit=2, etag304=1");
    }

    @Test
    void persistentWatchFailureFailsTheCliInsteadOfContinuingToEvaluation(CapturedOutput output) {
        Fixture cli = new Fixture();
        cli.list(config("old", OLD));
        for (int attempt = 0; attempt < 4; attempt++) {
            cli.watches.add(offline());
        }

        assertThrows(RuntimeException.class, () -> cli.run(1));

        assertThat(cli.watchTimes).containsExactly(0L, 300L, 600L, 900L);
        assertThat(cli.sleeps).containsExactly(300L, 300L, 300L, 100L);
        assertThat(cli.cursors).containsExactly(0L, 0L, 0L, 0L);
        assertThat(output).contains("reason=recovery-budget-exhausted", "confirmedCursor=unconfirmed, pendingSync=true",
                        "recoveryElapsedMs=1000", "HTTP_503")
                .doesNotContain("Evaluating feature...");
    }

    @Test
    void persistentBreakerRejectionsUseTheSameBoundedRecovery(CapturedOutput output) {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(1);
        cli.watch(true, 2);
        cli.list(offline());
        for (int attempt = 0; attempt < 3; attempt++) {
            cli.list(() -> { throw HttpRequestFailedException.circuitOpen("state=OPEN"); });
        }

        assertThrows(RuntimeException.class, () -> cli.run(2));

        assertThat(cli.configTimes).containsExactly(0L, 0L, 0L, 300L, 600L, 900L);
        assertThat(output).contains("reason=recovery-budget-exhausted, observedRevision=2, confirmedCursor=1, pendingSync=true",
                "recoveryElapsedMs=1000, failure=CIRCUIT_OPEN");
    }

    @Test
    void watchRecoveryDoesNotRestartTheBudgetWhenConfigStillFails(CapturedOutput output) {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(1);
        cli.watches.add(offline());
        cli.watches.add(() -> {
            cli.advance(600);
            return observation(true, 2);
        });
        cli.list(offline());

        assertThrows(RuntimeException.class, () -> cli.run(2));

        assertThat(cli.cursors).containsExactly(0L, 1L, 1L);
        assertThat(cli.configTimes).containsExactly(0L, 0L, 900L);
        assertThat(cli.sleeps).containsExactly(300L, 100L);
        assertThat(output).contains("reason=recovery-budget-exhausted, observedRevision=2, confirmedCursor=1, pendingSync=true",
                "recoveryElapsedMs=1000");
    }

    @Test
    void watchReturningAfterDeadlineCannotStartAnotherRead(CapturedOutput output) {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(1);
        cli.watches.add(offline());
        cli.watches.add(() -> {
            cli.advance(800);
            return observation(true, 2);
        });

        assertThrows(RuntimeException.class, () -> cli.run(2));

        assertThat(cli.configTimes).hasSize(2);
        assertThat(output).contains("reason=recovery-budget-exhausted, observedRevision=2, confirmedCursor=1, pendingSync=true",
                "recoveryElapsedMs=1100");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateReadConfirmsOnlyWhenTheThreadWasNotCancelled(boolean cancel, CapturedOutput output) throws Exception {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(1);
        cli.watch(true, 2);
        cli.list(offline());
        cli.list(() -> {
            cli.advance(900);
            if (cancel) {
                Thread.currentThread().interrupt();
            }
            return config("new", NEW);
        });

        if (cancel) {
            try {
                assertThrows(InterruptedException.class, () -> cli.run(2));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
            assertThat(cli.cached().body).isEqualTo(OLD);
            assertThat(output).contains("reason=cancelled, observedRevision=2, confirmedCursor=1, pendingSync=true")
                    .doesNotContain("Evaluating feature...");
        } else {
            cli.run(2);
            assertThat(cli.cached().body).isEqualTo(NEW);
            assertThat(output).contains("confirmedCursor=2, pendingSync=false", "recoveryElapsedMs=1200",
                    "Watch ended: reason=completed");
        }
        assertThat(cli.configTimes).containsExactly(0L, 0L, 0L, 300L);
    }

    @Test
    void cancellingAnOuterWaitPreservesSignalAndStartsNoMoreRequests(CapturedOutput output) {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(1);
        cli.watch(true, 2);
        cli.list(offline());
        cli.sleeper = nanos -> { throw new InterruptedException("cancelled wait"); };

        try {
            assertThrows(InterruptedException.class, () -> cli.run(2));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(cli.configTimes).hasSize(3);
        assertThat(cli.cursors).containsExactly(0L, 1L);
        assertThat(output).contains("reason=cancelled, observedRevision=2, confirmedCursor=1, pendingSync=true")
                .doesNotContain("Evaluating feature...");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void firstEmptyObservationRechecksEvenWhenInitialReadWasStaleOrBeforeAReset(
            boolean staleInitialRead, CapturedOutput output) throws Exception {
        Fixture cli = new Fixture();
        cli.cache.put(CONFIG_URL, "\"old\"", OLD);
        if (staleInitialRead) {
            cli.list(offline());
        } else {
            cli.list(config("old", OLD));
        }
        cli.watch(false, 0);
        cli.list(config("empty", EMPTY));
        cli.watch(false, 0);

        cli.run(2);

        assertThat(cli.cursors).containsExactly(0L, 0L);
        assertThat(cli.configTimes).hasSize(2);
        assertThat(cli.cached().body).isEqualTo(EMPTY);
        assertThat(output).contains("observedRevision=0, confirmedCursor=unconfirmed, pendingSync=true",
                "observedRevision=0, confirmedCursor=0, pendingSync=false", "Watch round 2/2 complete");
    }

    @Test
    void lowerRevisionNeedsAReadThenLaterWritesAreObserved(CapturedOutput output) throws Exception {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(7);
        cli.watch(false, 1);
        cli.list(offline());
        cli.list(config("reset", EMPTY));
        cli.watch(true, 2);
        cli.list(config("new", NEW));

        cli.run(3);

        assertThat(cli.cursors).containsExactly(0L, 7L, 1L);
        assertThat(cli.cached().body).isEqualTo(NEW);
        assertThat(output).containsSubsequence("observedRevision=1, confirmedCursor=7, pendingSync=true",
                "observedRevision=1, confirmedCursor=1, pendingSync=false",
                "observedRevision=2, confirmedCursor=2, pendingSync=false");
    }

    @Test
    void concurrentWritesDoNotAdvanceTheCursorBeyondTheEvidence(CapturedOutput output) throws Exception {
        Fixture cli = new Fixture();
        cli.list(config("old", OLD));
        AtomicLong serverRevision = new AtomicLong(1);
        cli.watches.add(() -> {
            long observed = serverRevision.getAndIncrement(); // Write 2 after taking the Watch observation.
            return observation(true, observed);
        });
        cli.list(() -> {
            long readRevision = serverRevision.getAndIncrement(); // Write 3 after taking the list snapshot.
            return config("second", body("value-at-" + readRevision));
        });
        cli.watches.add(() -> observation(true, serverRevision.get()));
        cli.list(config("third", body("value-at-3")));
        cli.watch(false, 3);

        cli.run(3);

        assertThat(cli.cursors).containsExactly(0L, 1L, 3L);
        assertThat(output).containsSubsequence("value-at-2", "observedRevision=1, confirmedCursor=1",
                "value-at-3", "observedRevision=3, confirmedCursor=3");
        assertThat(cli.configTimes).hasSize(3);
    }

    @ParameterizedTest
    @CsvSource({"400,true", "403,false", "404,false", "429,true"})
    void terminalClientErrorsStopWithoutRecovery(int status, boolean atWatch, CapturedOutput output) {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(1);
        Supplier<ResponseEntity<String>> fail = () -> {
            throw new HttpRequestFailedException("HTTP_" + status, status, false);
        };
        if (atWatch) {
            cli.watches.add(fail);
        } else {
            cli.watch(true, 2);
            cli.list(fail);
        }

        var error = assertThrows(HttpRequestFailedException.class, () -> cli.run(2));

        assertThat(error.getStatusCode()).isEqualTo(status);
        assertThat(cli.sleeps).isEmpty();
        assertThat(output).contains("reason=terminal-error", "confirmedCursor=1", "failure=HTTP_" + status)
                .doesNotContain("Evaluating feature...");
    }

    @Test
    void malformedRefreshDoesNotConfirmTheObservation(CapturedOutput output) {
        Fixture cli = new Fixture();
        cli.initiallyConfirmOldConfig(1);
        cli.watch(true, 2);
        cli.list(ResponseEntity.ok("{\"code\":0,\"data\":{}}"));

        assertThrows(HttpRequestFailedException.class, () -> cli.run(2));

        assertThat(cli.sleeps).isEmpty();
        assertThat(cli.cached().body).isEqualTo(OLD);
        assertThat(output).contains("reason=terminal-error, observedRevision=2, confirmedCursor=1, pendingSync=true",
                "CONFIG_DATA_TYPE");
    }

    @Test
    void unconditional304DoesNotConfirmTheObservation(CapturedOutput output) {
        Fixture cli = new Fixture();
        cli.list(ResponseEntity.ok(OLD));
        cli.watch(true, 1);
        cli.list(ResponseEntity.status(304).build());

        assertThrows(HttpRequestFailedException.class, () -> cli.run(1));

        assertThat(cli.validators).containsExactly(null, null);
        assertThat(cli.sleeps).isEmpty();
        assertThat(output).contains("reason=terminal-error, observedRevision=1, confirmedCursor=unconfirmed, pendingSync=true",
                "HTTP_304_WITHOUT_REQUEST_VALIDATOR");
    }

    @CsvSource({"true,0", "false,5"})
    @ParameterizedTest
    void disabledAndZeroRoundInvocationsKeepTheOneShotBehavior(boolean enabled, int rounds, CapturedOutput output)
            throws Exception {
        Fixture cli = new Fixture();
        cli.list(config("old", OLD));

        cli.run(enabled, rounds);

        assertThat(cli.cursors).isEmpty();
        assertThat(cli.configTimes).hasSize(1);
        assertThat(output).contains("Evaluating feature...", "cacheHit=0, etag304=0").doesNotContain("Watch ended");
    }

    private static Supplier<ResponseEntity<String>> offline() {
        return () -> { throw new HttpRequestFailedException("HTTP_503", 503, true); };
    }

    private static String body(String value) {
        return "{\"code\":0,\"data\":[{\"value\":\"" + value + "\"}]}";
    }

    private static ResponseEntity<String> config(String tag, String body) {
        return ResponseEntity.ok().eTag("\"" + tag + "\"").body(body);
    }

    private static ResponseEntity<String> observation(boolean changed, long revision) {
        return ResponseEntity.ok("{\"code\":0,\"data\":{\"changed\":" + changed
                + ",\"latestVersion\":" + revision + "}}");
    }

    private static final class Fixture {
        final ConfigCache cache = new HttpDiskCacheStub();
        final Queue<Supplier<ResponseEntity<String>>> configResponses = new ArrayDeque<>();
        final Queue<Supplier<ResponseEntity<String>>> watches = new ArrayDeque<>();
        final List<Long> cursors = new ArrayList<>();
        final List<Long> configTimes = new ArrayList<>();
        final List<Long> watchTimes = new ArrayList<>();
        final List<Long> sleeps = new ArrayList<>();
        final List<String> validators = new ArrayList<>();
        long elapsedNanos;
        WatchSession.Sleeper sleeper = nanos -> {
            sleeps.add(TimeUnit.NANOSECONDS.toMillis(nanos));
            elapsedNanos += nanos;
        };

        void advance(long millis) {
            elapsedNanos += TimeUnit.MILLISECONDS.toNanos(millis);
        }

        void list(ResponseEntity<String> response) {
            list(() -> response);
        }

        void list(Supplier<ResponseEntity<String>> response) {
            configResponses.add(response);
        }

        void watch(boolean changed, long revision) {
            watches.add(() -> observation(changed, revision));
        }

        void initiallyConfirmOldConfig(long revision) {
            list(config("old", OLD));
            watch(true, revision);
            list(ResponseEntity.status(304).eTag("W/\"old\"").build());
        }

        HttpDiskCache.Entry cached() {
            return cache.get(CONFIG_URL);
        }

        void run(int rounds) throws Exception {
            run(true, rounds);
        }

        void run(boolean watchEnabled, int rounds) throws Exception {
            HttpFetcher standard = (url, etag) -> {
                if (url.contains("/evaluate?")) {
                    return ResponseEntity.ok("{\"code\":0,\"data\":{}}");
                }
                assertThat(url).isEqualTo(CONFIG_URL);
                configTimes.add(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                validators.add(etag);
                return configResponses.remove().get();
            };
            HttpFetcher watch = (url, etag) -> {
                cursors.add(Long.parseLong(url.split("sinceVersion=")[1].split("&")[0]));
                watchTimes.add(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
                return watches.remove().get();
            };
            ObjectMapper mapper = new ObjectMapper();
            var timing = new WatchSession.Timing(TimeUnit.SECONDS.toNanos(1), TimeUnit.MILLISECONDS.toNanos(300),
                    () -> elapsedNanos, sleeper);
            var runner = new DemoRunner(new ConfigClient(standard, watch, cache, mapper), standard, mapper, timing);
            Map.of(
                    "baseUrl", BASE_URL, "app", "app", "env", "dev", "featureName", "demo", "userId", "user",
                    "watchEnabled", watchEnabled, "timeoutSeconds", 1, "rounds", rounds
            ).forEach((field, value) -> ReflectionTestUtils.setField(runner, field, value));
            runner.run();
        }
    }

    private static final class HttpDiskCacheStub implements ConfigCache {
        private HttpDiskCache.Entry entry;

        @Override
        public HttpDiskCache.Entry get(String url) {
            assertThat(url).isEqualTo(CONFIG_URL);
            return entry;
        }

        @Override
        public void put(String url, String etag, String body) {
            assertThat(url).isEqualTo(CONFIG_URL);
            entry = new HttpDiskCache.Entry(etag, body);
        }
    }
}
