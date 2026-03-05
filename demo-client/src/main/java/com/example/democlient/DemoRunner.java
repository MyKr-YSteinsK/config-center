package com.example.democlient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class DemoRunner implements CommandLineRunner {

    private final RestTemplate restTemplate = new RestTemplate();
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

        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        if (cachedEtag != null) {
            // 用最直接的方式设置，避免引号被二次处理导致不匹配
            headers.set("If-None-Match", cachedEtag);
        }

        org.springframework.http.HttpEntity<Void> entity = new org.springframework.http.HttpEntity<>(headers);
        org.springframework.http.ResponseEntity<String> resp =
                restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);

        if (resp.getStatusCode().value() == 304) {
            System.out.println("304 Not Modified -> use cached body");

            if (cachedBody == null) {
                System.out.println("WARN: got 304 but no cached body, refetch without If-None-Match");

                org.springframework.http.ResponseEntity<String> fresh =
                        restTemplate.exchange(url, org.springframework.http.HttpMethod.GET,
                                org.springframework.http.HttpEntity.EMPTY, String.class);

                String body = fresh.getBody();
                if (body == null) {
                    throw new IllegalStateException("Refetch returned empty body, cannot recover cache");
                }
                String etag = fresh.getHeaders().getETag();

                HttpDiskCache.put(url, etag, body);

                System.out.println(mapper.readTree(body).toPrettyString());
            } else {
                System.out.println(mapper.readTree(cachedBody).toPrettyString());
            }

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
        String eval = restTemplate.getForObject(
                baseUrl + "/api/features/evaluate?app=" + app + "&env=" + env
                        + "&name=" + featureName + "&userId=" + userId,
                String.class
        );
        JsonNode evalNode = mapper.readTree(eval);
        System.out.println(evalNode.toPrettyString());
    }
}