package com.example.democlient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoRunner implements CommandLineRunner {

    private final ReliableHttp http = new ReliableHttp(
            //超时调小模拟网络拥塞验证
            //测试得到WARN: network error, retry in 234ms:
            800,   // connect timeout ms
            1200,  // read timeout ms
            new RetryPolicy(3, 200, 2000) // 尝试3次：初始200ms，最多2s
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
    }
}