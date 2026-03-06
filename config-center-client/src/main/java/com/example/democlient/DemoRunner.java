package com.example.democlient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoRunner implements CommandLineRunner {

    private final ReliableHttp http = new ReliableHttp(
            800,
            1200,
            // 故意把超时设得偏小一点，方便在本地模拟网络抖动和重试。
            new RetryPolicy(3, 200, 2000),
            new CircuitBreaker(2, 5000)
    );
    private final ObjectMapper mapper = new ObjectMapper();

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

    @Override
    public void run(String... args) throws Exception {

        int cacheHit = 0;
        int etagHit304 = 0;
        System.out.println("=== Demo Client ===");
        System.out.println("\nFetching configs...");

        String url = baseUrl + "/api/configs?app=" + app + "&env=" + env;

        // 先读磁盘缓存。服务端哪怕短暂不可用，客户端也别直接裸奔报错。
        HttpDiskCache.Entry cached = HttpDiskCache.get(url);
        String cachedEtag = cached == null ? null : cached.etag;
        String cachedBody = cached == null ? null : cached.body;

        org.springframework.http.ResponseEntity<String> resp;
        try {
            resp = http.getWithRetry(url, cachedEtag);
        } catch (Exception e) {
            System.out.println("ERROR: fetch configs failed, fallback to cached body: " + e.getMessage());
            if (cachedBody == null) {
                throw e;
            }
            cacheHit++;
            System.out.println(mapper.readTree(cachedBody).toPrettyString());
            resp = null;
        }

        String latestBody = cachedBody;
        if (resp == null) {
            // 已经走降级输出，这里不再重复处理。
        } else if (resp.getStatusCode().value() == 304) {
            etagHit304++;
            cacheHit++;
            System.out.println("304 Not Modified -> use cached body");
            System.out.println(mapper.readTree(cachedBody).toPrettyString());
            latestBody = cachedBody;
        } else {
            String body = resp.getBody();
            String etag = resp.getHeaders().getETag();
            if (etag != null && body != null) {
                HttpDiskCache.put(url, etag, body);
            }
            System.out.println("200 OK -> cache etag=" + etag);
            System.out.println(mapper.readTree(body).toPrettyString());
            latestBody = body;
        }

        long sinceVersion = 0;
        try {
            // 从最新这份配置响应里找最大 version，后面的 watch 就从这里往后等。
            JsonNode lastBodyNode = latestBody == null ? null : mapper.readTree(latestBody);
            if (lastBodyNode != null && lastBodyNode.has("data") && lastBodyNode.get("data").isArray()) {
                for (JsonNode item : lastBodyNode.get("data")) {
                    long v = item.path("version").asLong(0);
                    sinceVersion = Math.max(sinceVersion, v);
                }
            }
        } catch (Exception ignore) {
            // 这里只是尽力取 version，取不到也不影响主流程继续跑。
        }

        if (watchEnabled) {
            System.out.println("\nWatching config changes (long polling) ...");
            for (int i = 0; i < rounds; i++) {
                String watchUrl = baseUrl + "/api/configs/watch?app=" + app + "&env=" + env
                        + "&sinceVersion=" + sinceVersion + "&timeoutSeconds=" + timeoutSeconds;

                try {
                    org.springframework.http.ResponseEntity<String> watchResp = http.getWithRetry(watchUrl, null);
                    JsonNode node = mapper.readTree(watchResp.getBody());
                    boolean changed = node.path("data").path("changed").asBoolean(false);
                    long latestVersion = node.path("data").path("latestVersion").asLong(sinceVersion);

                    System.out.println("watch result: changed=" + changed + ", latestVersion=" + latestVersion);

                    if (changed) {
                        sinceVersion = latestVersion;
                        System.out.println("change detected -> refetch configs");
                    }
                } catch (Exception e) {
                    System.out.println("WARN: watch failed: " + e.getMessage());
                }
            }
        }

        System.out.println("\nEvaluating feature...");
        String evalUrl = baseUrl + "/api/features/evaluate?app=" + app + "&env=" + env
                + "&name=" + featureName + "&userId=" + userId;

        try {
            org.springframework.http.ResponseEntity<String> evalResp = http.getWithRetry(evalUrl, null);
            JsonNode evalNode = mapper.readTree(evalResp.getBody());
            System.out.println(evalNode.toPrettyString());
        } catch (Exception e) {
            System.out.println("ERROR: evaluate failed: " + e.getMessage());
        }

        System.out.println("\n=== Metrics Summary ===");
        System.out.println("cacheHit=" + cacheHit + ", etag304=" + etagHit304);
    }
}
