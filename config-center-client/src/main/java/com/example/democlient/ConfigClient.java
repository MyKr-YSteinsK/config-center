package com.example.democlient;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class ConfigClient {

    private static final Pattern ENTITY_TAG = Pattern.compile("(?:W/)?\"[\\x21\\x23-\\x7e\\x80-\\xff]*\"");

    private final HttpFetcher standardHttp;
    private final HttpFetcher watchHttp;
    private final ConfigCache cache;
    private final ObjectReader json;

    public ConfigClient(
            @Qualifier("standardHttp") HttpFetcher standardHttp,
            @Qualifier("watchHttp") HttpFetcher watchHttp,
            ConfigCache cache,
            ObjectMapper mapper) {
        this.standardHttp = standardHttp;
        this.watchHttp = watchHttp;
        this.cache = cache;
        this.json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public FetchResult fetchConfigs(String url) throws InterruptedException {
        return readConfigs(url).result();
    }

    ReadResult readConfigs(String url) throws InterruptedException {
        ReliableHttp.checkInterrupted();
        HttpDiskCache.Entry cached = usableCachedConfig(cache.get(url));
        String validator = cached == null ? null : entityTag(cached.etag);

        ResponseEntity<String> response;
        try {
            response = standardHttp.getWithRetry(url, validator);
        } catch (HttpRequestFailedException e) {
            ReliableHttp.checkInterrupted();
            if (e.isCacheFallbackAllowed() && cached != null) {
                return new ReadResult(new FetchResult(cached.body, true, false), e);
            }
            throw e;
        }
        ReliableHttp.checkInterrupted();

        int status = response.getStatusCode().value();
        if (status == 304) {
            if (cached == null) {
                throw new HttpRequestFailedException("HTTP_304_WITHOUT_VALID_CACHE", 304, false);
            }
            if (validator == null) {
                throw new HttpRequestFailedException("HTTP_304_WITHOUT_REQUEST_VALIDATOR", 304, false);
            }
            String responseTag = response.getHeaders().getFirst(HttpHeaders.ETAG);
            if (responseTag != null && !weakTagMatches(validator, entityTag(responseTag))) {
                throw new HttpRequestFailedException("HTTP_304_ETAG_MISMATCH", 304, false);
            }
            return new ReadResult(new FetchResult(cached.body, true, true), null);
        }
        if (status != 200) {
            throw new HttpRequestFailedException("HTTP_" + status, status, false);
        }

        String body = response.getBody();
        requireConfigList(body);
        ReliableHttp.checkInterrupted();
        cache.put(url, response.getHeaders().getETag(), body);
        return new ReadResult(new FetchResult(body, false, false), null);
    }

    private static String entityTag(String value) {
        if (value == null) {
            return null;
        }
        String tag = value.trim();
        return ENTITY_TAG.matcher(tag).matches() ? tag : null;
    }

    private static boolean weakTagMatches(String validator, String responseTag) {
        return responseTag != null && validator.substring(validator.indexOf('"'))
                .equals(responseTag.substring(responseTag.indexOf('"')));
    }

    private HttpDiskCache.Entry usableCachedConfig(HttpDiskCache.Entry cached) {
        if (cached == null || cached.body == null) {
            return null;
        }

        try {
            requireConfigList(cached.body);
            return cached;
        } catch (HttpRequestFailedException ignored) {
            return null;
        }
    }

    private void requireConfigList(String body) {
        if (!requireSuccessfulData(body, "CONFIG").isArray()) {
            throw protocolError("CONFIG_DATA_TYPE");
        }
    }

    public WatchResult watchOnce(String watchUrl, String configUrl)
            throws Exception {
        WatchObservation observation = observeWatch(watchUrl);
        FetchResult refreshed = observation.changed() ? fetchConfigs(configUrl) : null;
        return new WatchResult(observation.changed(), observation.latestVersion(), refreshed);
    }

    WatchObservation observeWatch(String url) throws InterruptedException {
        ReliableHttp.checkInterrupted();
        ResponseEntity<String> response = watchHttp.getWithRetry(url, null);
        ReliableHttp.checkInterrupted();
        int status = response.getStatusCode().value();
        if (status != 200) {
            throw new HttpRequestFailedException("HTTP_" + status + "_UNEXPECTED_WATCH_STATUS", status, false);
        }
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

        return new WatchObservation(changedNode.booleanValue(), latestVersionNode.longValue());
    }

    JsonNode requireSuccessfulData(String body, String responseType) {
        if (body == null) {
            throw protocolError(responseType + "_WITHOUT_BODY");
        }

        JsonNode root;
        try {
            root = json.readTree(body);
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

    public record FetchResult(String body, boolean fromCache, boolean notModified) {
        String source() {
            if (notModified) {
                return "etag-304";
            }
            return fromCache ? "stale-cache" : "http-200";
        }
    }

    record ReadResult(FetchResult result, HttpRequestFailedException fallbackFailure) {}

    record WatchObservation(boolean changed, long latestVersion) {}

    public record WatchResult(boolean changed, long latestVersion, FetchResult refreshed) {}
}
