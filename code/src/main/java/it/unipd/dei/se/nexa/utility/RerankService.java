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
import java.util.List;
import java.util.Map;

public final class RerankService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI serviceUri;

    public RerankService(final String serviceUrl) {
        if (serviceUrl == null || serviceUrl.isBlank()) {
            throw new IllegalArgumentException("reRankServiceUrl must be configured.");
        }

        this.httpClient = HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper();
        this.serviceUri = URI.create(serviceUrl);
    }

    public float[] rerank(final String query, final List<String> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return new float[0];
        }

        final String requestBody = objectMapper.writeValueAsString(Map.of(
                "query", query,
                "documents", documents
        ));

        final HttpRequest request = HttpRequest.newBuilder(serviceUri)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            final HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Rerank request failed with status "
                        + response.statusCode() + ": " + response.body());
            }

            final JsonNode root = objectMapper.readTree(response.body());
            final JsonNode scoresNode = root.path("scores");

            if (scoresNode.isArray()) {
                float[] scores = new float[scoresNode.size()];
                for (int i = 0; i < scoresNode.size(); i++) {
                    scores[i] = (float) scoresNode.get(i).asDouble();
                }
                return scores;
            }
            throw new IOException("Rerank response did not contain a valid scores array.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Rerank request was interrupted.", e);
        }
    }
}
