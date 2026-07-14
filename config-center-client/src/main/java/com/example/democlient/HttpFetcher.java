package com.example.democlient;

import org.springframework.http.ResponseEntity;

public interface HttpFetcher {

    ResponseEntity<String> getWithRetry(String url, String ifNoneMatch) throws InterruptedException;
}
