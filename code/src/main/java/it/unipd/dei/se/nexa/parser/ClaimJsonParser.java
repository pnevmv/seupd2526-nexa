package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Streaming parser for claim/topic JSON files.
 */
public final class ClaimJsonParser implements Iterator<Claim>, Iterable<Claim>, AutoCloseable {

    private static final String JSON_INDEX = "index";
    private static final String JSON_TEXT = "text";
    private static final String JSON_PUBKEY = "pubkey";
    private static final String JSON_ORIGINAL_TEXT = "original_text";

    private Reader reader;
    private com.fasterxml.jackson.core.JsonParser jsonParser;
    private final ObjectMapper objectMapper;
    private final String language;
    private Path sourcePath;
    private final ParserStats stats = new ParserStats();

    private Claim nextClaim;
    private boolean closed;
    private int recordNumber;

    public ClaimJsonParser(final Reader in, final String language) {
        if (in == null) {
            throw new NullPointerException(CommonParser.NULL_READER);
        }

        this.reader = new BufferedReader(in);
        this.objectMapper = new ObjectMapper();
        this.language = language;

        initializeParser();
    }

    public static ClaimJsonParser open(final Path path) throws IOException {
        ClaimJsonParser parser = new ClaimJsonParser(Files.newBufferedReader(path), inferLanguage(path));
        parser.sourcePath = path;
        return parser;
    }

    @Override
    public Iterator<Claim> iterator() {
        return this;
    }

    @Override
    public boolean hasNext() {
        return nextClaim != null;
    }

    @Override
    public Claim next() {
        if (nextClaim == null) {
            throw new NoSuchElementException("No more claims to parse.");
        }

        Claim current = nextClaim;
        try {
            advance();
        } catch (IOException e) {
            throw new IllegalStateException("Error parsing claims JSON", e);
        }
        return current;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            jsonParser.close();
            reader.close();
        }
    }

    private void advance() throws IOException {
        nextClaim = null;

        JsonToken token = jsonParser.nextToken();
        while (token != null && token != JsonToken.END_ARRAY) {
            if (token == JsonToken.START_OBJECT) {
                recordNumber++;
                stats.recordTotal();
                JsonNode node = objectMapper.readTree(jsonParser);
                Claim claim = parseClaim(node, recordNumber);
                if (claim.getText() != null && !claim.getText().isBlank()) {
                    nextClaim = claim;
                    stats.recordValid();
                    return;
                }
                stats.recordSkipped();
                stats.recordMissingText();
                System.err.printf("Skipping malformed claim record #%d: missing usable text.%n", recordNumber);
            }
            token = jsonParser.nextToken();
        }

        close();
    }

    private Claim parseClaim(final JsonNode node, final int fallbackIndex) throws IOException {
        Claim claim = objectMapper.treeToValue(node, Claim.class);

        if (!node.hasNonNull(JSON_INDEX)) {
            claim.setIndex(fallbackIndex);
            stats.recordMissingIdentifier();
            System.err.printf("Claim record #%d has no index; using sequential fallback.%n", fallbackIndex);
        }
        if (!node.hasNonNull(JSON_PUBKEY)) {
            claim.setPubkey(null);
            stats.recordMissingGold();
        }
        if (node.hasNonNull(JSON_ORIGINAL_TEXT)) {
            claim.setOriginalText(node.get(JSON_ORIGINAL_TEXT).asText(""));
        }
        if (claim.getOriginalText() == null && node.has(JSON_TEXT)) {
            claim.setOriginalText(node.get(JSON_TEXT).asText(""));
        }
        claim.setLanguage(language);

        return claim;
    }

    public ParserStats getStats() {
        return stats;
    }

    public void reset() throws IOException {
        if (sourcePath == null) {
            throw new UnsupportedOperationException("This parser cannot be reset without a source path.");
        }
        close();
        this.reader = new BufferedReader(Files.newBufferedReader(sourcePath));
        this.nextClaim = null;
        this.closed = false;
        this.recordNumber = 0;
        this.stats.reset();
        initializeParser();
    }

    private void initializeParser() {
        try {
            this.jsonParser = new JsonFactory().createParser(this.reader);
            JsonToken token = jsonParser.nextToken();
            if (token != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Error in JSON format: expected start array");
            }
            advance();
        } catch (IOException e) {
            throw new IllegalStateException("Error reading claims JSON", e);
        }
    }

    public static String inferLanguage(final Path path) {
        if (path == null || path.getFileName() == null) {
            return null;
        }

        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.startsWith("fr_")) {
            return "fr";
        }
        if (fileName.startsWith("de_")) {
            return "de";
        }
        if (fileName.startsWith("en_")) {
            return "en";
        }

        return null;
    }

    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("Usage: ClaimJsonParser <claimsJson>");
            return;
        }

        int count = 0;
        try (ClaimJsonParser parser = ClaimJsonParser.open(Path.of(args[0]))) {
            for (Claim ignored : parser) {
                count++;
            }
            System.out.println(parser.getStats());
        }

        System.out.printf("Parsed %d claim(s).%n", count);
    }
}
