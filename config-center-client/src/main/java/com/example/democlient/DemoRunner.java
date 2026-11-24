package com.example.democlient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class DemoRunner implements CommandLineRunner {

    private final ConfigClient configClient;
    private final HttpFetcher standardHttp;
    private final ObjectMapper mapper;
    private final WatchSession.Timing watchTiming;

    @Value("${demo.baseUrl}")
    private String baseUrl;

    @Value("${demo.app}")
    private String app;

    @Value("${demo.env}")
    private String env;

    @Value("${demo.featureName}")
    private String featureName;

    @Value("${demo.userId}")
    private String userId;

    @Value("${demo.watch.enabled:true}")
    private boolean watchEnabled;

    @Value("${demo.watch.timeoutSeconds:10}")
    private int timeoutSeconds;

    @Value("${demo.watch.rounds:5}")
    private int rounds;

    public DemoRunner(ConfigClient configClient,
                      @Qualifier("standardHttp") HttpFetcher standardHttp,
                      ObjectMapper mapper,
                      WatchSession.Timing watchTiming) {
        this.configClient = configClient;
        this.standardHttp = standardHttp;
        this.mapper = mapper;
        this.watchTiming = watchTiming;
    }

    @Override
    public void run(String... args) throws Exception {
        FetchOutput output = new FetchOutput();
        System.out.println("=== Config Center Client ===");

        try {
            String configUrl = buildConfigUrl(baseUrl, app, env);
            if (watchEnabled && rounds > 0) {
                new WatchSession(configClient, configUrl,
                        cursor -> buildWatchUrl(baseUrl, app, env, cursor, timeoutSeconds),
                        watchTiming, output::print).run(rounds);
            } else {
                output.print("initial fetch", configClient.fetchConfigs(configUrl));
            }
            evaluateFeature();
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw cancelled;
        } finally {
            output.printMetrics();
        }
    }

    private void evaluateFeature() throws InterruptedException {
        ReliableHttp.checkInterrupted();
        System.out.println("\nEvaluating feature...");
        String evalUrl = buildEvaluationUrl(baseUrl, app, env, featureName, userId);
        try {
            var response = standardHttp.getWithRetry(evalUrl, null);
            ReliableHttp.checkInterrupted();
            configClient.requireSuccessfulData(response.getBody(), "FEATURE_EVALUATION");
            System.out.println(mapper.readTree(response.getBody()).toPrettyString());
        } catch (InterruptedException cancelled) {
            throw cancelled;
        } catch (Exception failure) {
            ReliableHttp.checkInterrupted();
            System.out.println("ERROR: evaluate failed: " + failure.getMessage());
        }
    }

    private final class FetchOutput {
        private int cacheHit;
        private int etagHit304;

        void print(String label, ConfigClient.FetchResult result) throws JsonProcessingException {
            if (result.fromCache()) cacheHit++;
            if (result.notModified()) etagHit304++;
            System.out.println(label + ": fromCache=" + result.fromCache()
                    + ", notModified=" + result.notModified() + ", source=" + result.source());
            System.out.println(mapper.readTree(result.body()).toPrettyString());
        }

        void printMetrics() {
            System.out.println("\n=== Metrics Summary ===");
            System.out.println("cacheHit=" + cacheHit + ", etag304=" + etagHit304);
        }
    }

    static String buildConfigUrl(String baseUrl, String app, String env) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("api", "configs")
                .encode()
                .queryParam("app", "{app}")
                .queryParam("env", "{env}")
                .buildAndExpand(app, env)
                .toUriString();
    }

    static String buildWatchUrl(
            String baseUrl, String app, String env, long sinceVersion, int timeoutSeconds) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("api", "configs", "watch")
                .encode()
                .queryParam("app", "{app}")
                .queryParam("env", "{env}")
                .queryParam("sinceVersion", "{sinceVersion}")
                .queryParam("timeoutSeconds", "{timeoutSeconds}")
                .buildAndExpand(app, env, sinceVersion, timeoutSeconds)
                .toUriString();
    }

    static String buildEvaluationUrl(
            String baseUrl, String app, String env, String featureName, String userId) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .pathSegment("api", "features", "evaluate")
                .encode()
                .queryParam("app", "{app}")
                .queryParam("env", "{env}")
                .queryParam("name", "{featureName}")
                .queryParam("userId", "{userId}")
                .buildAndExpand(app, env, featureName, userId)
                .toUriString();
    }
}
