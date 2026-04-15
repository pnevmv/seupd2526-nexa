package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.util.NoSuchElementException;

public class JsonParser extends CommonParser {

    private static final String JSON_PUBKEY = "pubkey";
    private static final String JSON_TITLE = "title";
    private static final String JSON_ABSTRACT = "abstract";
    private static final String JSON_VENUE = "venue";
    private static final String JSON_AUTHORS = "authors";

    private final com.fasterxml.jackson.core.JsonParser jsonParser;
    private final Iterator<JsonNode> docIterator;

    public JsonParser(Reader in) {
        super(new BufferedReader(in));

        ObjectMapper objectMapper = new ObjectMapper();
        JsonFactory factory = new JsonFactory();

        try {
            jsonParser = factory.createParser(this.in);

            if (jsonParser.currentToken() == null) {
                jsonParser.nextToken();
            }

            if (jsonParser.currentToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Error in JSON format: expected start array.");
            }

            JsonNode root = objectMapper.readTree(jsonParser);

            if (root == null || !root.isArray()) {
                throw new IllegalStateException("Error in JSON format: root element must be an array.");
            }

            docIterator = root.iterator();
            this.hasNextPublication = docIterator.hasNext();

        } catch (IOException e) {
            throw new IllegalStateException("Error reading JSON.", e);
        }
    }

    @Override
    protected Publication parse() {
        if (!docIterator.hasNext()) {
            this.hasNextPublication = false;
            throw new NoSuchElementException("No more JSON documents to parse.");
        }

        JsonNode node = docIterator.next();
        this.hasNextPublication = docIterator.hasNext();

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
    }
}