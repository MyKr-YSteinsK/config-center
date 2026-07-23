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
        HttpDiskCache.Entry cached = usableCachedConfig(cache.get(url));
        String cachedEtag = cached == null || cached.etag == null || cached.etag.isBlank()
                ? null : cached.etag;

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
        JsonNode data = requireSuccessfulData(body, "CONFIG");
        if (!data.isArray()) {
            throw protocolError("CONFIG_DATA_TYPE");
        }

        cache.put(url, response.getHeaders().getETag(), body);
        return new FetchResult(body, false, false);
    }

    private HttpDiskCache.Entry usableCachedConfig(HttpDiskCache.Entry cached) {
        if (cached == null || cached.body == null) {
            return null;
        }

        try {
            return requireSuccessfulData(cached.body, "CONFIG").isArray() ? cached : null;
        } catch (HttpRequestFailedException ignored) {
            return null;
        }
    }

    public WatchResult watchOnce(String watchUrl, long sinceVersion, String configUrl)
            throws Exception {
        ResponseEntity<String> response = watchHttp.getWithRetry(watchUrl, null);
        JsonNode data = requireSuccessfulData(response.getBody(), "WATCH");
        if (!data.isObject()) {
            throw protocolError("WATCH_DATA_TYPE");
        }

        JsonNode changedNode = data.get("changed");
        JsonNode latestVersionNode = data.get("latestVersion");
        if (changedNode == null || !changedNode.isBoolean()) {
            throw protocolError("WATCH_CHANGED_TYPE");
        }
        if (latestVersionNode == null || !latestVersionNode.isIntegralNumber()
                || !latestVersionNode.canConvertToLong() || latestVersionNode.longValue() < 0) {
            throw protocolError("WATCH_LATEST_VERSION_TYPE");
        }

        boolean changed = changedNode.booleanValue();
        long latestVersion = latestVersionNode.longValue();
        FetchResult refreshed = changed ? fetchConfigs(configUrl) : null;
        return new WatchResult(changed, latestVersion, refreshed);
    }

    JsonNode requireSuccessfulData(String body, String responseType) {
        if (body == null) {
            throw protocolError(responseType + "_WITHOUT_BODY");
        }

        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw protocolError(responseType + "_INVALID_JSON", e);
        }

        if (root == null || !root.isObject()) {
            throw protocolError(responseType + "_ROOT_TYPE");
        }

        JsonNode code = root.get("code");
        JsonNode data = root.get("data");
        if (code == null || !code.isIntegralNumber()
                || !code.canConvertToInt() || code.intValue() != 0) {
            throw protocolError(responseType + "_CODE");
        }
        if (data == null || data.isNull()) {
            throw protocolError(responseType + "_DATA_MISSING");
        }
        return data;
    }

    private HttpRequestFailedException protocolError(String detail) {
        return new HttpRequestFailedException("HTTP_200_PROTOCOL_ERROR: " + detail, 200, false);
    }

    private HttpRequestFailedException protocolError(String detail, Throwable cause) {
        return new HttpRequestFailedException(
                "HTTP_200_PROTOCOL_ERROR: " + detail, 200, false, cause);
    }

    public record FetchResult(String body, boolean fromCache, boolean notModified) {}

    public record WatchResult(boolean changed, long latestVersion, FetchResult refreshed) {}
}
