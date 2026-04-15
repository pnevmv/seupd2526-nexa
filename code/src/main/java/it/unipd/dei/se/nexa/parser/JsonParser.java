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
        String pubJsonInput = "[" +
                "  {" +
                "    \"pubkey\": 999," +
                "    \"title\": \"Stress Test Evoluto\"," +
                "    \"abstract\": \"Abstract: Benvenuti su https://nexa.dei.unipd.it! <p>Tag HTML rimosso.</p> " +
                "Citazioni quadre [1, 2-5] e citazioni autore (Rossi et al., 2023). " +
                "Entità HTML: p &lt; 0.05 &amp; spazio&nbsp;unito. " +
                "Simboli: 37°C e 0.5±0.1. Sezioni: Methods: i risultati sono ottimi. " +
                "Emoji finali: 🚀🔥🏥🧪\"," +
                "    \"venue\": \"Journal of AI Stress Tests\"," +
                "    \"authors\": \"A. Collaborator, B. Assistant\"" +
                "  }" +
                "]";

        String claimJsonInput = "[" +
                "  {" +
                "    \"index\":5," +
                "    \"text\":\"@KYT_ThatsME @EricTopol @NathanGrubaugh @angie_rasmussen @DrDenaGrayson @Laurie_Garrett @R_H_Ebright @Ayjchan @DrEricDing @DrZoeHyde CDC EVALI study only monitored cases w\\\\/ a record of vaping in previous 90 days & with seriousness necessitating hospital admission. But at the time of the first outbreak in WI & IL, there were 2x the ER visits for mysterious, critical lung disease in 11-34yo. 🚨\","
                +
                "    \"pubkey\":8474" +
                "  }" +
                "]";

        System.out.println("=== TEST PUBLICATION PARSER ===");
        try (java.io.StringReader reader = new java.io.StringReader(pubJsonInput)) {
            it.unipd.dei.se.nexa.parser.JsonParser p = new it.unipd.dei.se.nexa.parser.JsonParser(reader);
            for (it.unipd.dei.se.nexa.parser.Publication d : p) {
                System.out.printf("%n%s%n", d.toString());
            }
        } catch (Exception e) {
            System.err.println("Error during Publication parsing: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\n=== TEST CLAIM PARSER ===");
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Claim[] claims = mapper.readValue(claimJsonInput, Claim[].class);
            for (Claim c : claims) {
                System.out.printf("%n%s%n", c.toString());
            }
        } catch (Exception e) {
            System.err.println("Error during Claim parsing: " + e.getMessage());
            e.printStackTrace();
        }
    }
}