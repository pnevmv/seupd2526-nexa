package it.unipd.dei.se.nexa.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class JsonParserCollectionTestLauncher {

    private static final Path COLLECTION_PATH = Path.of("datasets/collection_data.json");
    private static final int PREVIEW_LIMIT = 5;

    public static void main(String[] args) {
        int totalDocuments = 0;
        int emptyTitles = 0;
        int emptyAbstracts = 0;
        int emptyVenues = 0;
        int emptyAuthors = 0;

        System.out.println("=== JSON PARSER TEST ON REAL COLLECTION ===");
        System.out.println("Collection path: " + COLLECTION_PATH.toAbsolutePath());
        System.out.println();

        try (BufferedReader reader = Files.newBufferedReader(COLLECTION_PATH, StandardCharsets.UTF_8)) {
            JsonParser parser = new JsonParser(reader);

            for (Publication publication : parser) {
                totalDocuments++;

                String title = publication.getTitle();
                String abstractText = publication.getAbstract();
                String venue = publication.getVenue();
                String authors = publication.getAuthors();

                if (title == null || title.isBlank()) {
                    emptyTitles++;
                }
                if (abstractText == null || abstractText.isBlank() || "#".equals(abstractText)) {
                    emptyAbstracts++;
                }
                if (venue == null || venue.isBlank()) {
                    emptyVenues++;
                }
                if (authors == null || authors.isBlank()) {
                    emptyAuthors++;
                }

                if (totalDocuments <= PREVIEW_LIMIT) {
                    System.out.println("----- DOCUMENT " + totalDocuments + " -----");
                    System.out.println(publication);
                    System.out.println();
                }

                if (totalDocuments % 10000 == 0) {
                    System.out.println("Parsed documents: " + totalDocuments);
                }
            }

            System.out.println("=== PARSING COMPLETED SUCCESSFULLY ===");
            System.out.println("Total documents parsed: " + totalDocuments);
            System.out.println("Documents with empty title: " + emptyTitles);
            System.out.println("Documents with empty abstract: " + emptyAbstracts);
            System.out.println("Documents with empty venue: " + emptyVenues);
            System.out.println("Documents with empty authors: " + emptyAuthors);

        } catch (IOException e) {
            System.err.println("I/O error while reading collection: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Parser failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}