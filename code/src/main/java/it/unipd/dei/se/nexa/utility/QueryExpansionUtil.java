package it.unipd.dei.se.nexa.utility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public final class QueryExpansionUtil {

    private static final String ENABLE_QUERY_EXPANSION_KEY = "enableQueryExpansion";
    private static final String SERVICE_URL_KEY = "queryExpansionServiceUrl";
    private static final String DEFAULT_SERVICE_URL = "http://127.0.0.1:8001/expand";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private static final ConfigManager GLOBAL_CONFIG = ConfigManager.getGlobalConfig();
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private QueryExpansionUtil() {
    }

    public static String expandQuery(final String query) {
        if (query == null || query.isBlank()) {
            return query;
        }

        // Keep the original query unless expansion is explicitly enabled in config.
        if (!Boolean.TRUE.equals(GLOBAL_CONFIG.getBool(ENABLE_QUERY_EXPANSION_KEY))) {
            return query;
        }

        try {
            final String expandedQuery = requestExpandedQuery(query);
            return combineOriginalAndExpanded(query, expandedQuery);
        } catch (IllegalStateException e) {
            System.err.println("Query expansion failed; using original query. " + e.getMessage());
            return query;
        }
    }

    private static String requestExpandedQuery(final String query) {
        final String serviceUrl = resolveServiceUrl();

        try {
            final String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of("query", query));
            // The expansion service accepts a simple JSON payload with the raw user query.
            final HttpRequest request = HttpRequest.newBuilder(URI.create(serviceUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            final HttpResponse<String> response = HTTP_CLIENT.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Query expansion request failed with status "
                        + response.statusCode() + ": " + response.body());
            }

            final JsonNode root = OBJECT_MAPPER.readTree(response.body());
            // The service is expected to expose the rewritten query in the "expanded" field.
            final JsonNode expandedNode = root.path("expanded");
            if (expandedNode.isMissingNode()) {
                throw new IOException("Query expansion response did not contain an expanded field.");
            }

            return expandedNode.asText().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Query expansion request was interrupted.", e);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to expand query via " + serviceUrl + ".", e);
        }
    }

    private static String resolveServiceUrl() {
        final String configuredUrl = GLOBAL_CONFIG.getString(SERVICE_URL_KEY);
        // Use a local default endpoint when the config does not override the service URL.
        return configuredUrl == null || configuredUrl.isBlank() ? DEFAULT_SERVICE_URL : configuredUrl;
    }

    private static String combineOriginalAndExpanded(final String originalQuery,
                                                     final String expandedQuery) {
        if (expandedQuery == null || expandedQuery.isBlank()) {
            return originalQuery;
        }

        final String original = originalQuery.trim();
        final String expanded = expandedQuery.trim();
        if (original.equalsIgnoreCase(expanded)) {
            return original;
        }

        return original + " " + expanded;
    }
}
