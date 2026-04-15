package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.NoSuchElementException;

public class JsonParser extends CommonParser {

    private static final String JSON_PUBKEY = "pubkey";
    private static final String JSON_TITLE = "title";
    private static final String JSON_ABSTRACT = "abstract";
    private static final String JSON_VENUE = "venue";
    private static final String JSON_AUTHORS = "authors";

    private final com.fasterxml.jackson.core.JsonParser jsonParser;
    private final ObjectMapper objectMapper;

    public JsonParser(Reader in) {
        super(new BufferedReader(in));

        this.objectMapper = new ObjectMapper();

        try {
            JsonFactory factory = new JsonFactory();
            this.jsonParser = factory.createParser(this.in);

            JsonToken firstToken = this.jsonParser.nextToken();
            if (firstToken != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Error in JSON format: expected start array.");
            }

            JsonToken nextToken = this.jsonParser.nextToken();
            this.hasNextPublication = nextToken == JsonToken.START_OBJECT;

            if (nextToken != null && nextToken != JsonToken.START_OBJECT && nextToken != JsonToken.END_ARRAY) {
                throw new IllegalStateException("Error in JSON format: expected JSON object inside array.");
            }

        } catch (IOException e) {
            throw new IllegalStateException("Error reading JSON.", e);
        }
    }

    @Override
    protected Publication parse() {
        if (!hasNextPublication) {
            throw new NoSuchElementException("No more JSON documents to parse.");
        }

        try {
            JsonNode node = objectMapper.readTree(jsonParser);

            JsonToken nextToken = jsonParser.nextToken();
            hasNextPublication = nextToken == JsonToken.START_OBJECT;

            if (nextToken != null && nextToken != JsonToken.START_OBJECT && nextToken != JsonToken.END_ARRAY) {
                throw new IllegalStateException("Error in JSON format: expected JSON object or end of array.");
            }

            int pubkey = node.hasNonNull(JSON_PUBKEY) ? node.get(JSON_PUBKEY).asInt() : 0;
            String title = node.hasNonNull(JSON_TITLE) ? node.get(JSON_TITLE).asText() : "";
            String abstractText = node.hasNonNull(JSON_ABSTRACT) ? node.get(JSON_ABSTRACT).asText() : "";
            String venue = node.hasNonNull(JSON_VENUE) ? node.get(JSON_VENUE).asText() : "";
            String authors = node.hasNonNull(JSON_AUTHORS) ? node.get(JSON_AUTHORS).asText() : "";

            abstractText = cleanScientificText(abstractText);

            return new Publication(
                    pubkey,
                    title,
                    abstractText.isEmpty() ? "#" : abstractText,
                    venue,
                    authors
            );

        } catch (IOException e) {
            throw new IllegalStateException("Error parsing next JSON publication.", e);
        }
    }
}