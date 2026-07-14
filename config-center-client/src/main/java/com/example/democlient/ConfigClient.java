package com.example.democlient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class ConfigClient {

    private final HttpFetcher standardHttp;
    private final HttpFetcher watchHttp;
    private final ConfigCache cache;
    private final ObjectMapper mapper;

    @Autowired
    public ConfigClient(
            @Qualifier("standardHttp") HttpFetcher standardHttp,
            @Qualifier("watchHttp") HttpFetcher watchHttp,
            ConfigCache cache) {
        this(standardHttp, watchHttp, cache, new ObjectMapper());
    }

    ConfigClient(HttpFetcher standardHttp, HttpFetcher watchHttp,
                 ConfigCache cache, ObjectMapper mapper) {
        this.standardHttp = standardHttp;
        this.watchHttp = watchHttp;
        this.cache = cache;
        this.mapper = mapper;
    }

    public FetchResult fetchConfigs(String url) throws InterruptedException {
        HttpDiskCache.Entry cached = cache.get(url);
        String cachedEtag = cached == null ? null : cached.etag;

        ResponseEntity<String> response;
        try {
            response = standardHttp.getWithRetry(url, cachedEtag);
        } catch (HttpRequestFailedException e) {
            if (e.isCacheFallbackAllowed() && cached != null) {
                return new FetchResult(cached.body, true, false);
            }
            throw e;
        }

        if (response.getStatusCode().value() == 304) {
            if (cached == null || cached.body == null) {
                throw new HttpRequestFailedException(
                        "HTTP_304_WITHOUT_VALID_CACHE", 304, false);
            }
            return new FetchResult(cached.body, true, true);
        }

        String body = response.getBody();
        if (body == null) {
            throw new HttpRequestFailedException("HTTP_200_WITHOUT_BODY", 200, false);
        }

        cache.put(url, response.getHeaders().getETag(), body);
        return new FetchResult(body, false, false);
    }

    public WatchResult watchOnce(String watchUrl, long sinceVersion, String configUrl)
            throws Exception {
        ResponseEntity<String> response = watchHttp.getWithRetry(watchUrl, null);
        if (response.getBody() == null) {
            throw new HttpRequestFailedException("WATCH_RESPONSE_WITHOUT_BODY", 200, false);
        }

        JsonNode data = mapper.readTree(response.getBody()).path("data");
        boolean changed = data.path("changed").asBoolean(false);
        long latestVersion = data.path("latestVersion").asLong(sinceVersion);
        FetchResult refreshed = changed ? fetchConfigs(configUrl) : null;
        return new WatchResult(changed, latestVersion, refreshed);
    }

    public record FetchResult(String body, boolean fromCache, boolean notModified) {}

    public record WatchResult(boolean changed, long latestVersion, FetchResult refreshed) {}
}
