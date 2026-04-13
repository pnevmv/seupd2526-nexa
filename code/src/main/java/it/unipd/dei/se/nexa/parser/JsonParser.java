package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.Iterator;

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

            if (jsonParser.currentName() == null) {
                jsonParser.nextToken();
            }

            if (jsonParser.currentToken() != null && jsonParser.currentToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Error in JSON format: expected start array");
            }

            final JsonNode root = objectMapper.readTree(jsonParser);
            docIterator = root.iterator();

            this.next = docIterator.hasNext();

        } catch (IOException e) {
            throw new IllegalStateException("Error reading JSON", e);
        }
    }

    @Override
    protected Publication parse() {
        if (!docIterator.hasNext()) {
            this.next = false;
            return null;
        }

        JsonNode node = docIterator.next();

        this.next = docIterator.hasNext();

        int pubkey = node.hasNonNull(JSON_PUBKEY) ? node.get(JSON_PUBKEY).asInt() : 0;
        String title = node.hasNonNull(JSON_TITLE) ? node.get(JSON_TITLE).asText() : "";
        String abstractText = node.hasNonNull(JSON_ABSTRACT) ? node.get(JSON_ABSTRACT).asText() : "";
        String venue = node.hasNonNull(JSON_VENUE) ? node.get(JSON_VENUE).asText() : "";
        String authors = node.hasNonNull(JSON_AUTHORS) ? node.get(JSON_AUTHORS).asText() : "";

        abstractText = cleanText(abstractText);

        return new Publication(pubkey, title, abstractText.isEmpty() ? "#" : abstractText, venue, authors);
    }

    public static void main(String[] args) {
        String jsonInput = "[" +
                "  {" +
                "    \"pubkey\": 999," +
                "    \"title\": \"Stress Test Evoluto\"," +
                "    \"abstract\": \"Abstract: Benvenuti su https://nexa.dei.unipd.it! <p>Tag HTML rimosso.</p> " +
                "Citazioni quadre [1, 2-5] e citazioni autore (Rossi et al., 2023). " +
                "Entità HTML: p &lt; 0.05 &amp; spazio&nbsp;unito. " +
                "Simboli: 37°C e 0.5±0.1. Sezioni: Methods: i risultati sono ottimi. " +
                "Emoji finali: 🚀🔥🏥🧪\"," + // <-- nota: qui chiudiamo il JSON con \",
                "    \"venue\": \"Journal of AI Stress Tests\"," +
                "    \"authors\": \"A. Collaborator, B. Assistant\"" +
                "  }" +
                "]";

        try (java.io.StringReader reader = new java.io.StringReader(jsonInput)) {
            it.unipd.dei.se.nexa.parser.JsonParser p = new it.unipd.dei.se.nexa.parser.JsonParser(reader);

            for (it.unipd.dei.se.nexa.parser.Publication d : p) {
                System.out.printf("%n%n------------------------------------%n%s%n%n%n", d.toString());
            }
        } catch (Exception e) {
            System.err.println("Errore durante il parsing della stringa: " + e.getMessage());
            e.printStackTrace();
        }
    }
}