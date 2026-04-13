package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class JsonParser extends PublicationParser {

    private static final String JSON_PUBKEY = "pubkey";
    private static final String JSON_TITLE = "title";
    private static final String JSON_ABSTRACT = "abstract";
    private static final String JSON_VENUE = "venue";
    private static final String JSON_AUTHORS = "authors";

    private final com.fasterxml.jackson.core.JsonParser jsonParser;
    private final Iterator<JsonNode> pubIterator;

    private static final Pattern HTML = Pattern.compile("<[^>]*>");
    private static final Pattern URL = Pattern.compile("https?://[\\w./]+\\w+");

    public static String removeHtmlTags(String input) throws NullPointerException {
        if (input == null) throw new NullPointerException("Input string cannot be null.");
        Matcher matcher = HTML.matcher(input);
        return matcher.replaceAll("");
    }

    public static String removeUrls(String input) throws NullPointerException {
        if (input == null) throw new NullPointerException("Input string cannot be null.");
        Matcher matcher = URL.matcher(input);
        return matcher.replaceAll("");
    }

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
            pubIterator = root.iterator();

            this.next = pubIterator.hasNext();

        } catch (IOException e) {
            throw new IllegalStateException("Error reading JSON", e);
        }
    }

    @Override
    protected Publication parse() {
        if (!pubIterator.hasNext()) {
            this.next = false;
            return null;
        }

        JsonNode node = pubIterator.next();

        this.next = pubIterator.hasNext();

        int pubkey = node.hasNonNull(JSON_PUBKEY) ? node.get(JSON_PUBKEY).asInt() : 0;
        String title = node.hasNonNull(JSON_TITLE) ? node.get(JSON_TITLE).asText() : "";
        String abstractText = node.hasNonNull(JSON_ABSTRACT) ? node.get(JSON_ABSTRACT).asText() : "";
        String venue = node.hasNonNull(JSON_VENUE) ? node.get(JSON_VENUE).asText() : "";
        String authors = node.hasNonNull(JSON_AUTHORS) ? node.get(JSON_AUTHORS).asText() : "";

        try {
            abstractText = removeHtmlTags(abstractText);
        } catch (NullPointerException e) {
            System.err.println("Html clean: Input string is null.");
        }

        try {
            abstractText = removeUrls(abstractText);
        } catch (NullPointerException e) {
            System.err.println("Url clean: Input string is null.");
        }

        return new Publication(pubkey, title, abstractText.isEmpty() ? "#" : abstractText, venue, authors);
    }

    public static void main(String[] args) throws Exception {

        String jsonInput = "[" +
                "  {" +
                "    \"pubkey\": 123," +
                "    \"title\": \"Esempio di Titolo\"," +
                "    \"abstract\": \"Questo è un abstract con <p>tag HTML</p> e un link https://google.com\"," +
                "    \"venue\": \"Conferenza AI\"," +
                "    \"authors\": \"Mario Rossi, Luigi Bianchi\"" +
                "  }" +
                "]";

        try (StringReader reader = new StringReader(jsonInput)) {
            JsonParser p = new JsonParser(reader);

            for (Publication d : p) {
                System.out.printf("%n%n------------------------------------%n%s%n%n%n", d.toString());
            }
        } catch (Exception e) {
            System.err.println("Errore durante il parsing della stringa: " + e.getMessage());
            e.printStackTrace();
        }
    }
}