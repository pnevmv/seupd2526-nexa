package it.unipd.dei.se.nexa.tools;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WordNetPrologToSynonymsConverter {

    /**
     * Example WordNet Prolog line:
     * s(100001740,1,'entity',n,1,0).
     * s(100021939,1,'physical_entity',n,1,0).
     *
     * Group 1 = synset id
     * Group 2 = word
     */
    private static final Pattern WORDNET_S_PATTERN =
            Pattern.compile("^s\\((\\d+),\\d+,'((?:''|[^'])*)',[a-z],\\d+,\\d+\\)\\.$");

    /**
     * If false, entries containing spaces after normalization are skipped.
     * Example: "credit card"
     */
    private static final boolean KEEP_MULTIWORD_ENTRIES = false;

    /**
     * If true, convert tokens to lowercase.
     */
    private static final boolean LOWERCASE = true;

    private WordNetPrologToSynonymsConverter() {
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: java WordNetPrologToSynonymsConverter <input wn_s.pl> <output txt>");
            System.exit(1);
        }

        final Path inputPath = Path.of(args[0]);
        final Path outputPath = Path.of(args[1]);

        try {
            convert(inputPath, outputPath);
            System.out.println("Conversion completed successfully.");
            System.out.println("Input : " + inputPath.toAbsolutePath());
            System.out.println("Output: " + outputPath.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Conversion failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        }
    }

    public static void convert(final Path inputPath, final Path outputPath) throws IOException {
        if (inputPath == null) {
            throw new NullPointerException("Input path cannot be null.");
        }
        if (outputPath == null) {
            throw new NullPointerException("Output path cannot be null.");
        }
        if (!Files.isReadable(inputPath)) {
            throw new IllegalArgumentException("Input file is not readable: " + inputPath);
        }

        final Map<String, Set<String>> synsets = new LinkedHashMap<>();

        int totalLines = 0;
        int matchedLines = 0;
        int acceptedWords = 0;
        int skippedMultiword = 0;

        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;

                Matcher matcher = WORDNET_S_PATTERN.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }

                matchedLines++;

                final String synsetId = matcher.group(1);
                final String rawWord = matcher.group(2);

                final String normalizedWord = normalizeWord(rawWord);
                if (normalizedWord.isBlank()) {
                    continue;
                }

                if (!KEEP_MULTIWORD_ENTRIES && normalizedWord.contains(" ")) {
                    skippedMultiword++;
                    continue;
                }

                synsets.computeIfAbsent(synsetId, key -> new LinkedHashSet<>()).add(normalizedWord);
                acceptedWords++;
            }
        }

        int writtenGroups = 0;
        int skippedSingletons = 0;

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            for (Set<String> words : synsets.values()) {
                if (words.size() < 2) {
                    skippedSingletons++;
                    continue;
                }

                writer.write(String.join(", ", words));
                writer.newLine();
                writtenGroups++;
            }
        }

        System.out.println("Total lines read         : " + totalLines);
        System.out.println("WordNet 's(...)' lines   : " + matchedLines);
        System.out.println("Accepted words           : " + acceptedWords);
        System.out.println("Skipped multiword words  : " + skippedMultiword);
        System.out.println("Synset groups written    : " + writtenGroups);
        System.out.println("Skipped singleton groups : " + skippedSingletons);
    }

    private static String normalizeWord(final String rawWord) {
        if (rawWord == null) {
            return "";
        }

        String word = rawWord;

        // Prolog escapes apostrophe as doubled single quote: don''t -> don't
        word = word.replace("''", "'");

        // WordNet uses underscores for multiword expressions: credit_card
        word = word.replace('_', ' ');

        word = word.trim();

        if (LOWERCASE) {
            word = word.toLowerCase();
        }

        return word;
    }
}