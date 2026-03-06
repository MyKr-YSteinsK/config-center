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
            //超时调小模拟网络拥塞验证，测试得到WARN: network error, retry in 234ms:
            new RetryPolicy(3, 200, 2000),
            new CircuitBreaker(2, 5000) // 连续失败2次 -> 打开5秒
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

    @Override
    public void run(String... args) throws Exception {

        int cacheHit = 0;
        int etagHit304 = 0;
        System.out.println("=== Demo Client ===");
        System.out.println("\nFetching configs...");

        String url = baseUrl + "/api/configs?app=" + app + "&env=" + env;

        // 从落盘缓存读取
        HttpDiskCache.Entry cached = HttpDiskCache.get(url);
        String cachedEtag = cached == null ? null : cached.etag;
        String cachedBody = cached == null ? null : cached.body;

        org.springframework.http.ResponseEntity<String> resp;
        try {
            resp = http.getWithRetry(url, cachedEtag);
        } catch (Exception e) {
            // 降级：拉取失败，使用本地缓存继续跑
            System.out.println("ERROR: fetch configs failed, fallback to cached body: " + e.getMessage());
            if (cachedBody == null) {
                throw e; // 没缓存就只能失败（你也可以改成打印并返回）
            }
            System.out.println(mapper.readTree(cachedBody).toPrettyString());
            resp = null; // 后面不再处理
        }
        if (resp == null) {
            // 已降级输出过
        } else if (resp.getStatusCode().value() == 304) {
            System.out.println("304 Not Modified -> use cached body");
            System.out.println(mapper.readTree(cachedBody).toPrettyString());
        } else {
            String body = resp.getBody();
            String etag = resp.getHeaders().getETag();
            if (etag != null && body != null) {
                HttpDiskCache.put(url, etag, body);
            }
            System.out.println("200 OK -> cache etag=" + etag);
            System.out.println(mapper.readTree(body).toPrettyString());
        }

        // ===== Watch loop (long polling) =====
        boolean watchEnabled = true; // 先写死，后面接 @Value 再接
        int rounds = 5;
        int timeoutSeconds = 10;

        long sinceVersion = 0;
        try {
            // 从刚才打印的 configs JSON 中粗暴取一个 sinceVersion：取 data 里最大 version
            // 你当前 data 是列表结构，每个 item 有 version
            com.fasterxml.jackson.databind.JsonNode lastBodyNode;
            if (cachedBody != null) {
                lastBodyNode = mapper.readTree(cachedBody);
            } else {
                // 如果本次是 200，用 resp body；如果你没留变量，就先不做初始化，后面从缓存文件也行
                lastBodyNode = null;
            }
            if (lastBodyNode != null && lastBodyNode.has("data") && lastBodyNode.get("data").isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : lastBodyNode.get("data")) {
                    long v = item.path("version").asLong(0);
                    sinceVersion = Math.max(sinceVersion, v);
                }
            }
        } catch (Exception ignore) {}

        if (watchEnabled) {
            System.out.println("\nWatching config changes (long polling) ...");
            for (int i = 0; i < rounds; i++) {
                String watchUrl = baseUrl + "/api/configs/watch?app=" + app + "&env=" + env
                        + "&sinceVersion=" + sinceVersion + "&timeoutSeconds=" + timeoutSeconds;

                try {
                    org.springframework.http.ResponseEntity<String> watchResp = http.getWithRetry(watchUrl, null);
                    com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(watchResp.getBody());
                    boolean changed = node.path("data").path("changed").asBoolean(false);
                    long latestVersion = node.path("data").path("latestVersion").asLong(sinceVersion);

                    System.out.println("watch result: changed=" + changed + ", latestVersion=" + latestVersion);

                    if (changed) {
                        sinceVersion = latestVersion;
                        // 发生变更 -> 再拉一次 configs（会走 ETag/304/降级链路）
                        System.out.println("change detected -> refetch configs");
                        // 直接复用你上面那段“Fetching configs...”逻辑（下一步我们会把它抽函数）
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