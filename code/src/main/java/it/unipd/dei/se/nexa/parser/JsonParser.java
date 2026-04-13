package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.text.StringEscapeUtils;

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
    private final Iterator<JsonNode> docIterator;

    private static final Pattern HTML = Pattern.compile("<[^>]*>");
    private static final Pattern URL = Pattern.compile("https?://[\\w./]+\\w+");
    private static final Pattern CITATION_SQUARE = Pattern.compile("\\[\\s*\\d+(?:(?:\\s*,\\s*|\\s*-\\s*|\\s*–\\s*)\\d+)*\\s*\\]");
    private static final Pattern CITATION_AUTHORS = Pattern.compile("\\([A-Za-z\\s\\.,]+et al\\.?.*?\\)");
    private static final Pattern SECTIONS = Pattern.compile("(?i)\\b(Abstract|Objective|Design|Setting|Participants|Results|Conclusions|Methods|Background)\\b\\s*:?");
    private static final Pattern EMOJI = Pattern.compile("[\\x{1F600}-\\x{1F64F}\\x{2700}-\\x{27BF}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F900}-\\x{1F9FF}\\x{2600}-\\x{26FF}]");

    public static String cleanText(String input) {
        if (input == null || input.isEmpty()) return input;

        // 1. Decode HTML entities
        input = StringEscapeUtils.unescapeHtml4(input);

        // 2. Remove HTML tags
        input = HTML.matcher(input).replaceAll(" ");

        // 3. Remove URLs
        input = URL.matcher(input).replaceAll(" ");

        // 4. Remove citations
        input = CITATION_SQUARE.matcher(input).replaceAll(" ");
        input = CITATION_AUTHORS.matcher(input).replaceAll(" ");

        // 5. Remove sections
        input = SECTIONS.matcher(input).replaceAll(" ");

        // 6. Symbols normalization
        input = input.replace('·', '.');
        input = input.replaceAll("[±≥°]", " ");

        // 7. Emojis
        input = EMOJI.matcher(input).replaceAll(" ");

        // 8. Spaces and newlines normalization
        input = input.replaceAll("\\s+", " ").trim();

        return input;
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

    public static void main(String[] args) throws Exception {
        String jsonInput = "[" +
                "  {" +
                "    \"pubkey\": 123," +
                "    \"title\": \"Esempio di Titolo\"," +
                "    \"abstract\": \"Questo è un abstract con <p>tag HTML</p> e un link https://google.com\\n\\nEd entità come p &lt; 0.0001 e MPV &amp; 60 e spazio&nbsp;nbsp.\\nAbstract: Citazioni come [1, 2], [1-3] oppure (Feldstein et al., 2011). Poi simboli 0.72±0.12, p=0·009 e temp 37°C e emoji 🚨 🏥\"," +
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