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

public final class EmbeddingService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI serviceUri;

    public EmbeddingService(final String serviceUrl) {
        if (serviceUrl == null || serviceUrl.isBlank()) {
            throw new IllegalArgumentException("embeddingsServiceUrl must be configured.");
        }

        this.httpClient = HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        this.objectMapper = new ObjectMapper();
        this.serviceUri = URI.create(serviceUrl);
    }

    public float[] getEmbedding(final String text) throws IOException {
        if (text == null || text.isBlank()) {
            return new float[0];
        }

        final String requestBody = objectMapper.writeValueAsString(Map.of(
                "texts", List.of(text)
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
                throw new IOException("Embedding request failed with status "
                        + response.statusCode() + ": " + response.body());
            }

            final JsonNode root = objectMapper.readTree(response.body());
            final JsonNode resultsNode = root.path("results");

            if (resultsNode.isArray() && !resultsNode.isEmpty()) {
                JsonNode vectorNode = resultsNode.get(0).path("embedding_multi");
                if (vectorNode.isArray()) {
                    float[] vector = new float[vectorNode.size()];
                    for (int i = 0; i < vectorNode.size(); i++) {
                        vector[i] = (float) vectorNode.get(i).asDouble();
                    }
                    return vector;
                }
            }
            throw new IOException("Embedding response did not contain a valid vector format.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding request was interrupted.", e);
        }
    }
}
