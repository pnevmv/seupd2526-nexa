package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ClaimDatasetTestLauncher {

    private static final int PREVIEW_LIMIT = 3;
    private static final Map<String, Path[]> DATASET_CANDIDATES = createDatasetCandidates();

    public static void main(String[] args) {
        final ObjectMapper mapper = new ObjectMapper();
        int totalClaims = 0;

        System.out.println("=== CLAIM DATASET TEST ON TRAIN/DEV SPLITS ===");
        System.out.println();

        try {
            for (Map.Entry<String, Path[]> entry : DATASET_CANDIDATES.entrySet()) {
                final String datasetName = entry.getKey();
                final Path datasetPath = resolveDatasetPath(datasetName, entry.getValue());
                final Claim[] claims = mapper.readValue(datasetPath.toFile(), Claim[].class);
                final DatasetStats stats = analyzeClaims(claims);

                totalClaims += claims.length;

                System.out.println("=== DATASET " + datasetName + " ===");
                System.out.println("Path: " + datasetPath.toAbsolutePath());
                System.out.println("Parsed claims: " + claims.length);
                System.out.println("Claims with empty text after cleaning: " + stats.emptyTexts());
                System.out.println("Claims with duplicate index: " + stats.duplicateIndexes());
                System.out.println("Claims with non-positive pubkey: " + stats.nonPositivePubkeys());
                System.out.println("Sample claims:");

                if (stats.samples().isEmpty()) {
                    System.out.println("<no claims>");
                } else {
                    for (String sample : stats.samples()) {
                        System.out.println(sample);
                    }
                }

                System.out.println();
            }

            System.out.println("=== CLAIM DATASET PARSING COMPLETED SUCCESSFULLY ===");
            System.out.println("Total claim files parsed: " + DATASET_CANDIDATES.size());
            System.out.println("Total claims parsed: " + totalClaims);
        } catch (Exception e) {
            System.err.println("Claim dataset test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static DatasetStats analyzeClaims(final Claim[] claims) {
        int emptyTexts = 0;
        int duplicateIndexes = 0;
        int nonPositivePubkeys = 0;

        final Set<Integer> seenIndexes = new HashSet<>();
        final List<String> samples = new ArrayList<>();

        for (Claim claim : claims) {
            if (claim.getText() == null || claim.getText().isBlank()) {
                emptyTexts++;
            }

            if (!seenIndexes.add(claim.getIndex())) {
                duplicateIndexes++;
            }

            if (claim.getPubkey() <= 0) {
                nonPositivePubkeys++;
            }

            if (samples.size() < PREVIEW_LIMIT) {
                samples.add(formatClaimSummary(claim));
            }
        }

        return new DatasetStats(emptyTexts, duplicateIndexes, nonPositivePubkeys, samples);
    }

    private static String formatClaimSummary(final Claim claim) {
        final String text = claim.getText() == null || claim.getText().isBlank()
                ? "<empty text>"
                : claim.getText().replaceAll("\\s+", " ").trim();

        return claim.getIndex() + " -> pubkey=" + claim.getPubkey() + " | " + truncate(text, 140);
    }

    private static String truncate(final String value, final int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength - 3) + "...";
    }

    private static Path resolveDatasetPath(final String datasetName, final Path[] candidates) {
        for (Path candidate : candidates) {
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Unable to locate dataset " + datasetName + " in expected directories.");
    }

    private static Map<String, Path[]> createDatasetCandidates() {
        final Map<String, Path[]> candidates = new LinkedHashMap<>();
        candidates.put("en_train", createPathCandidates("en_train.json"));
        candidates.put("en_dev", createPathCandidates("en_dev.json"));
        candidates.put("fr_train", createPathCandidates("fr_train.json"));
        candidates.put("fr_dev", createPathCandidates("fr_dev.json"));
        candidates.put("de_train", createPathCandidates("de_train.json"));
        candidates.put("de_dev", createPathCandidates("de_dev.json"));
        return candidates;
    }

    private static Path[] createPathCandidates(final String fileName) {
        return new Path[]{
                Path.of("../datasets/" + fileName),
                Path.of("datasets/" + fileName)
        };
    }

    private record DatasetStats(int emptyTexts,
                                int duplicateIndexes,
                                int nonPositivePubkeys,
                                List<String> samples) {
    }
}
