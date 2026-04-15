package it.unipd.dei.se.nexa.analyzer;

import it.unipd.dei.se.nexa.parser.JsonParser;
import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LanguageDistributionCollectionTestLauncher {

    private static final Path[] COLLECTION_CANDIDATES = {
            Path.of("../datasets/collection_data.json"),
            Path.of("datasets/collection_data.json")
    };

    private static final int PREVIEW_PER_LANGUAGE = 50;

    public static void main(String[] args) {
        final Path collectionPath = resolveCollectionPath();
        final Map<String, Integer> languageCounts = createLanguageCounter();
        final Map<String, List<String>> languageSamples = createLanguageSamples();
        int totalDocuments = 0;

        System.out.println("=== LANGUAGE DISTRIBUTION TEST ON REAL COLLECTION ===");
        System.out.println("Collection path: " + collectionPath.toAbsolutePath());
        System.out.println();

        try (BufferedReader reader = Files.newBufferedReader(collectionPath, StandardCharsets.UTF_8)) {
            JsonParser parser = new JsonParser(reader);

            for (Publication publication : parser) {
                totalDocuments++;
                final String detectedLanguage = LanguageDetectionUtil.detectPublicationLanguage(publication);
                languageCounts.merge(detectedLanguage, 1, Integer::sum);
                addSample(languageSamples, detectedLanguage, publication);

                if (totalDocuments % 10000 == 0) {
                    System.out.println("Processed documents: " + totalDocuments);
                }
            }

            System.out.println("=== LANGUAGE DETECTION COMPLETED SUCCESSFULLY ===");
            System.out.println("Total documents analyzed: " + totalDocuments);
            System.out.println("English publications: " + languageCounts.get(LanguageDetectionUtil.ENGLISH));
            System.out.println("French publications: " + languageCounts.get(LanguageDetectionUtil.FRENCH));
            System.out.println("German publications: " + languageCounts.get(LanguageDetectionUtil.GERMAN));
            System.out.println("Unknown publications: " + languageCounts.get(LanguageDetectionUtil.UNKNOWN));
            System.out.println();

            printLanguageSamples("English", LanguageDetectionUtil.ENGLISH, languageSamples);
            printLanguageSamples("French", LanguageDetectionUtil.FRENCH, languageSamples);
            printLanguageSamples("German", LanguageDetectionUtil.GERMAN, languageSamples);
            printLanguageSamples("Unknown", LanguageDetectionUtil.UNKNOWN, languageSamples);

        } catch (IOException e) {
            System.err.println("I/O error while reading collection: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Language distribution test failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Path resolveCollectionPath() {
        for (Path candidate : COLLECTION_CANDIDATES) {
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Unable to locate collection_data.json in expected dataset directories.");
    }

    private static Map<String, Integer> createLanguageCounter() {
        Map<String, Integer> counters = new LinkedHashMap<>();
        counters.put(LanguageDetectionUtil.ENGLISH, 0);
        counters.put(LanguageDetectionUtil.FRENCH, 0);
        counters.put(LanguageDetectionUtil.GERMAN, 0);
        counters.put(LanguageDetectionUtil.UNKNOWN, 0);
        return counters;
    }

    private static Map<String, List<String>> createLanguageSamples() {
        Map<String, List<String>> samples = new LinkedHashMap<>();
        samples.put(LanguageDetectionUtil.ENGLISH, new ArrayList<>());
        samples.put(LanguageDetectionUtil.FRENCH, new ArrayList<>());
        samples.put(LanguageDetectionUtil.GERMAN, new ArrayList<>());
        samples.put(LanguageDetectionUtil.UNKNOWN, new ArrayList<>());
        return samples;
    }

    private static void addSample(final Map<String, List<String>> languageSamples,
                                  final String detectedLanguage,
                                  final Publication publication) {
        final List<String> samples = languageSamples.computeIfAbsent(detectedLanguage, ignored -> new ArrayList<>());
        if (samples.size() >= PREVIEW_PER_LANGUAGE) {
            return;
        }

        samples.add(formatPublicationSummary(publication));
    }

    private static String formatPublicationSummary(final Publication publication) {
        final String title = publication.getTitle() == null || publication.getTitle().isBlank()
                ? "<empty title>"
                : publication.getTitle().replaceAll("\\s+", " ").trim();

        return publication.getPubkey() + " | " + truncate(title, 120);
    }

    private static String truncate(final String value, final int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength - 3) + "...";
    }

    private static void printLanguageSamples(final String languageLabel,
                                             final String languageCode,
                                             final Map<String, List<String>> languageSamples) {
        System.out.println(languageLabel + " sample publications:");

        final List<String> samples = languageSamples.get(languageCode);
        if (samples == null || samples.isEmpty()) {
            System.out.println("<no publications>");
            System.out.println();
            return;
        }

        for (String sample : samples) {
            System.out.println(sample);
        }
        System.out.println();
    }
}
