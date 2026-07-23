package com.example.democlient;

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
                      ObjectMapper mapper) {
        this.configClient = configClient;
        this.standardHttp = standardHttp;
        this.mapper = mapper;
    }

    @Override
    public void run(String... args) throws Exception {
        int cacheHit = 0;
        int etagHit304 = 0;
        System.out.println("=== Config Center Client ===");

        String configUrl = buildConfigUrl(baseUrl, app, env);
        ConfigClient.FetchResult initial = configClient.fetchConfigs(configUrl);
        if (initial.fromCache()) cacheHit++;
        if (initial.notModified()) etagHit304++;
        printFetch("initial fetch", initial);

        long sinceVersion = 0;
        if (watchEnabled) {
            System.out.println("\nWatching config changes (long polling) ...");
            for (int i = 0; i < rounds; i++) {
                String watchUrl = buildWatchUrl(
                        baseUrl, app, env, sinceVersion, timeoutSeconds);
                try {
                    ConfigClient.WatchResult result = configClient.watchOnce(
                            watchUrl, configUrl);
                    System.out.println("watch result: changed=" + result.changed()
                            + ", latestVersion=" + result.latestVersion());
                    if (result.changed()) {
                        sinceVersion = result.latestVersion();
                        ConfigClient.FetchResult refreshed = result.refreshed();
                        if (refreshed.fromCache()) cacheHit++;
                        if (refreshed.notModified()) etagHit304++;
                        printFetch("change detected -> refreshed configs", refreshed);
                    }
                } catch (Exception e) {
                    System.out.println("WARN: watch failed: " + e.getMessage());
                }
            }
        }

        System.out.println("\nEvaluating feature...");
        String evalUrl = buildEvaluationUrl(baseUrl, app, env, featureName, userId);
        try {
            var response = standardHttp.getWithRetry(evalUrl, null);
            configClient.requireSuccessfulData(response.getBody(), "FEATURE_EVALUATION");
            System.out.println(mapper.readTree(response.getBody()).toPrettyString());
        } catch (Exception e) {
            System.out.println("ERROR: evaluate failed: " + e.getMessage());
        }

        System.out.println("\n=== Metrics Summary ===");
        System.out.println("cacheHit=" + cacheHit + ", etag304=" + etagHit304);
    }

    private void printFetch(String label, ConfigClient.FetchResult result) throws Exception {
        System.out.println(label + ": fromCache=" + result.fromCache()
                + ", notModified=" + result.notModified());
        System.out.println(mapper.readTree(result.body()).toPrettyString());
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
