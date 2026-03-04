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
        String configs = restTemplate.getForObject(
                baseUrl + "/api/configs?app=" + app + "&env=" + env,
                String.class
        );
        System.out.println(mapper.readTree(configs).toPrettyString());

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