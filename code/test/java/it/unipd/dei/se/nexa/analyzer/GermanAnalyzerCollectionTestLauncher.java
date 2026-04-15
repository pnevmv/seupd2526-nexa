package it.unipd.dei.se.nexa.analyzer;

import it.unipd.dei.se.nexa.parser.JsonParser;
import it.unipd.dei.se.nexa.parser.Publication;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class GermanAnalyzerCollectionTestLauncher {

    private static final Path[] COLLECTION_CANDIDATES = {
            Path.of("../datasets/collection_data.json"),
            Path.of("datasets/collection_data.json")
    };

    private static final int PREVIEW_LIMIT = 5;
    private static final int MAX_PREVIEW_TOKENS = 20;

    public static void main(String[] args) {
        final Path collectionPath = resolveCollectionPath();

        int totalDocuments = 0;
        int emptyTitleTokens = 0;
        int emptyAbstractTokens = 0;
        long totalTitleTokens = 0;
        long totalAbstractTokens = 0;

        System.out.println("=== GERMAN ANALYZER TEST ON REAL COLLECTION ===");
        System.out.println("Collection path: " + collectionPath.toAbsolutePath());
        System.out.println();

        try (GermanAnalyzer analyzer = new GermanAnalyzer();
             BufferedReader reader = Files.newBufferedReader(collectionPath, StandardCharsets.UTF_8)) {

            JsonParser parser = new JsonParser(reader);

            for (Publication publication : parser) {
                totalDocuments++;

                AnalysisResult titleResult = analyzeText(analyzer, "title", publication.getTitle());
                AnalysisResult abstractResult = analyzeText(analyzer, "abstract", publication.getAbstract());

                totalTitleTokens += titleResult.tokenCount();
                totalAbstractTokens += abstractResult.tokenCount();

                if (titleResult.tokenCount() == 0) {
                    emptyTitleTokens++;
                }
                if (abstractResult.tokenCount() == 0) {
                    emptyAbstractTokens++;
                }

                if (totalDocuments <= PREVIEW_LIMIT) {
                    System.out.println("----- DOCUMENT " + totalDocuments + " -----");
                    System.out.println("Pubkey: " + publication.getPubkey());
                    System.out.println("Title: " + publication.getTitle());
                    System.out.println("Title tokens (" + titleResult.tokenCount() + "): " + titleResult.preview());
                    System.out.println("Abstract tokens (" + abstractResult.tokenCount() + "): " + abstractResult.preview());
                    System.out.println();
                }

                if (totalDocuments % 10000 == 0) {
                    System.out.println("Processed documents: " + totalDocuments);
                }
            }

            System.out.println("=== ANALYSIS COMPLETED SUCCESSFULLY ===");
            System.out.println("Total documents analyzed: " + totalDocuments);
            System.out.println("Documents with zero title tokens: " + emptyTitleTokens);
            System.out.println("Documents with zero abstract tokens: " + emptyAbstractTokens);
            System.out.printf("Average title tokens: %.2f%n", totalDocuments == 0 ? 0.0 : (double) totalTitleTokens / totalDocuments);
            System.out.printf("Average abstract tokens: %.2f%n", totalDocuments == 0 ? 0.0 : (double) totalAbstractTokens / totalDocuments);

        } catch (IOException e) {
            System.err.println("I/O error while reading collection: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("German analyzer test failed: " + e.getMessage());
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

    private static AnalysisResult analyzeText(final GermanAnalyzer analyzer,
                                              final String fieldName,
                                              final String text) throws IOException {
        String safeText = text == null ? "" : text;
        StringBuilder preview = new StringBuilder();
        int tokenCount = 0;

        try (TokenStream stream = analyzer.tokenStream(fieldName, new StringReader(safeText))) {
            CharTermAttribute termAttribute = stream.addAttribute(CharTermAttribute.class);
            stream.reset();

            while (stream.incrementToken()) {
                tokenCount++;
                if (tokenCount <= MAX_PREVIEW_TOKENS) {
                    if (preview.length() > 0) {
                        preview.append(" | ");
                    }
                    preview.append(termAttribute);
                }
            }

            stream.end();
        }

        if (preview.length() == 0) {
            preview.append("<no tokens>");
        }

        return new AnalysisResult(tokenCount, preview.toString());
    }

    private record AnalysisResult(int tokenCount, String preview) {
    }
}
