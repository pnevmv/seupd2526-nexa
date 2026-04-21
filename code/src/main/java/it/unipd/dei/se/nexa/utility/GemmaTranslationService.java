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

public final class GemmaTranslationService implements TranslationService {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI serviceUri;

    public GemmaTranslationService(final String serviceUrl) {
        if (serviceUrl == null || serviceUrl.isBlank()) {
            throw new IllegalArgumentException("translationServiceUrl must be configured when translationProvider=gemma.");
        }

        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.serviceUri = URI.create(serviceUrl);
    }

    @Override
    public String translate(final String text,
                            final String sourceLanguage,
                            final String targetLanguage) throws IOException {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (sourceLanguage == null || sourceLanguage.isBlank()
                || targetLanguage == null || targetLanguage.isBlank()) {
            throw new IllegalArgumentException("Source and target languages must be provided for translation.");
        }
        if (sourceLanguage.equalsIgnoreCase(targetLanguage)) {
            return text;
        }

        final String requestBody = objectMapper.writeValueAsString(Map.of(
                "source_lang_code", sourceLanguage,
                "target_lang_code", targetLanguage,
                "text", text
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
                throw new IOException("TranslateGemma request failed with status "
                        + response.statusCode() + ": " + response.body());
            }

            final JsonNode root = objectMapper.readTree(response.body());
            final JsonNode translationNode = root.path("translation");
            if (translationNode.isMissingNode() || translationNode.asText().isBlank()) {
                throw new IOException("TranslateGemma response did not contain a non-empty translation field.");
            }

            return translationNode.asText().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("TranslateGemma request was interrupted.", e);
        }
    }
}
