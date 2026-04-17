package it.unipd.dei.se.nexa.index;

import it.unipd.dei.se.nexa.analyzer.EnglishAnalyzer;
import it.unipd.dei.se.nexa.analyzer.FrenchAnalyzer;
import it.unipd.dei.se.nexa.analyzer.GermanAnalyzer;
import it.unipd.dei.se.nexa.parser.JsonParser;
import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DirectoryIndexerCollectionTestLauncher {

    private static final Path[] COLLECTION_CANDIDATES = {
            Path.of("../datasets/collection_data.json"),
            Path.of("datasets/collection_data.json")
    };
    private static final int MAX_SEARCH_TOKENS = 12;
    private static final int INDEX_PROGRESS_REPORT_INTERVAL = 100;

    public static void main(String[] args) {
        final Path collectionPath = resolveCollectionPath();
        Path stagedDocsDir = null;
        Path indexDir = null;

        System.out.println("=== DIRECTORY INDEXER FULL PIPELINE TEST ON REAL COLLECTION ===");
        System.out.println("Collection path: " + collectionPath.toAbsolutePath());
        System.out.println();

        try {
            final ExpectedIndexStats expectedStats = buildExpectedIndexStats(collectionPath);
            stagedDocsDir = stageCollectionInTemporaryDirectory(collectionPath);
            indexDir = Files.createTempDirectory("nexa-index-");

            System.out.println("Expected parsed documents: " + expectedStats.totalDocuments());
            System.out.println("Expected indexed language distribution: " + expectedStats.languageCounts());
            System.out.println("Search assertions prepared: " + expectedStats.searchExpectations().size());
            System.out.println("Temporary docs dir: " + stagedDocsDir.toAbsolutePath());
            System.out.println("Temporary index dir: " + indexDir.toAbsolutePath());
            System.out.println();

            DirectoryIndexer indexer = new DirectoryIndexer(stagedDocsDir, indexDir, INDEX_PROGRESS_REPORT_INTERVAL);
            indexer.index();

            validateIndex(indexDir, expectedStats);

            System.out.println();
            System.out.println("=== FULL PIPELINE TEST COMPLETED SUCCESSFULLY ===");
            System.out.println("Indexed documents: " + expectedStats.totalDocuments());
            System.out.println("Validated language distribution: " + expectedStats.languageCounts());
            System.out.println("Validated localized-field searches: " + expectedStats.searchExpectations().size());
        } catch (Exception e) {
            System.err.println("Full pipeline test failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        } finally {
            deleteRecursively(indexDir);
            deleteRecursively(stagedDocsDir);
        }
    }

    private static ExpectedIndexStats buildExpectedIndexStats(final Path collectionPath) throws IOException {
        final Map<String, Integer> languageCounts = createLanguageCounter();
        final List<SearchExpectation> searchExpectations = new ArrayList<>();

        try (EnglishAnalyzer englishAnalyzer = new EnglishAnalyzer();
             FrenchAnalyzer frenchAnalyzer = new FrenchAnalyzer();
             GermanAnalyzer germanAnalyzer = new GermanAnalyzer();
             BufferedReader reader = Files.newBufferedReader(collectionPath, StandardCharsets.UTF_8)) {

            final Map<String, Analyzer> analyzers = new LinkedHashMap<>();
            analyzers.put(LanguageDetectionUtil.ENGLISH, englishAnalyzer);
            analyzers.put(LanguageDetectionUtil.FRENCH, frenchAnalyzer);
            analyzers.put(LanguageDetectionUtil.GERMAN, germanAnalyzer);
            analyzers.put(LanguageDetectionUtil.UNKNOWN, englishAnalyzer);

            final JsonParser parser = new JsonParser(reader);
            int totalDocuments = 0;

            for (Publication publication : parser) {
                totalDocuments++;

                final String language = DirectoryIndexer.resolveIndexingLanguage(
                        LanguageDetectionUtil.detectPublicationLanguage(publication));
                languageCounts.merge(language, 1, Integer::sum);

                registerExpectationIfMissing(searchExpectations, analyzers, publication, language,
                        Publication.FIELD_TITLE, publication.getTitle());
                registerExpectationIfMissing(searchExpectations, analyzers, publication, language,
                        Publication.FIELD_ABSTRACT, publication.getAbstract());
            }

            ensureSearchCoverage(languageCounts, searchExpectations);
            return new ExpectedIndexStats(totalDocuments, languageCounts, searchExpectations);
        }
    }

    private static void registerExpectationIfMissing(final List<SearchExpectation> searchExpectations,
                                                     final Map<String, Analyzer> analyzers,
                                                     final Publication publication,
                                                     final String language,
                                                     final String baseFieldName,
                                                     final String text) throws IOException {
        if (hasExpectation(searchExpectations, language, baseFieldName)) {
            return;
        }

        final Analyzer analyzer = analyzers.get(language);
        if (analyzer == null) {
            return;
        }

        final String localizedFieldName = Publication.getLocalizedFieldName(baseFieldName, language);
        final List<String> candidateTokens = extractSearchTokens(analyzer, localizedFieldName, text);

        if (candidateTokens.isEmpty()) {
            return;
        }

        searchExpectations.add(new SearchExpectation(language, baseFieldName, localizedFieldName,
                publication.getPubkey(), candidateTokens));
    }

    private static void ensureSearchCoverage(final Map<String, Integer> languageCounts,
                                             final List<SearchExpectation> searchExpectations) {
        for (String language : List.of(LanguageDetectionUtil.ENGLISH,
                LanguageDetectionUtil.FRENCH,
                LanguageDetectionUtil.GERMAN)) {
            final int documentsInLanguage = languageCounts.getOrDefault(language, 0);
            if (documentsInLanguage == 0) {
                continue;
            }

            final boolean covered = searchExpectations.stream()
                    .anyMatch(expectation -> language.equals(expectation.language()));
            checkState(covered, "Unable to prepare any searchable sample for language " + language + ".");
        }
    }

    private static void validateIndex(final Path indexPath, final ExpectedIndexStats expectedStats) throws IOException {
        try (Directory directory = FSDirectory.open(indexPath);
             DirectoryReader reader = DirectoryReader.open(directory)) {

            checkState(reader.numDocs() == expectedStats.totalDocuments(),
                    "Indexed document count mismatch. Expected " + expectedStats.totalDocuments()
                            + " but found " + reader.numDocs() + ".");

            final Map<String, Integer> actualLanguageCounts = readIndexedLanguageCounts(reader);
            checkState(actualLanguageCounts.equals(expectedStats.languageCounts()),
                    "Indexed language distribution mismatch. Expected " + expectedStats.languageCounts()
                            + " but found " + actualLanguageCounts + ".");

            for (Map.Entry<String, Integer> entry : expectedStats.languageCounts().entrySet()) {
                if (entry.getValue() == 0) {
                    continue;
                }

                final String language = entry.getKey();
                final String titleField = Publication.getLocalizedFieldName(Publication.FIELD_TITLE, language);
                final String abstractField = Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, language);

                checkState(reader.getDocCount(titleField) > 0,
                        "Localized title field missing from index: " + titleField);
                checkState(reader.getDocCount(abstractField) > 0,
                        "Localized abstract field missing from index: " + abstractField);
            }

            final IndexSearcher searcher = new IndexSearcher(reader);
            for (SearchExpectation expectation : expectedStats.searchExpectations()) {
                boolean matched = false;

                for (String candidateToken : expectation.candidateTokens()) {
                    final Query query = new BooleanQuery.Builder()
                            .add(new TermQuery(new Term(expectation.localizedFieldName(), candidateToken)),
                                    BooleanClause.Occur.MUST)
                            .add(IntPoint.newExactQuery(Publication.FIELD_PUBKEY, expectation.pubkey()),
                                    BooleanClause.Occur.MUST)
                            .build();

                    final TopDocs hits = searcher.search(query, 1);
                    if (hits.totalHits.value() > 0) {
                        matched = true;
                        break;
                    }
                }

                checkState(matched,
                        "No indexed document matched any analyzed token " + expectation.candidateTokens()
                                + " on field " + expectation.localizedFieldName()
                                + " for pubkey " + expectation.pubkey() + ".");
            }
        }
    }

    private static Map<String, Integer> readIndexedLanguageCounts(final DirectoryReader reader) throws IOException {
        final Map<String, Integer> languageCounts = createLanguageCounter();
        final StoredFields storedFields = reader.storedFields();

        for (int docId = 0; docId < reader.maxDoc(); docId++) {
            final Document document = storedFields.document(docId);
            final String language = document.get(Publication.FIELD_LANGUAGE);
            languageCounts.merge(language == null ? LanguageDetectionUtil.UNKNOWN : language, 1, Integer::sum);
        }

        return languageCounts;
    }

    private static Path resolveCollectionPath() {
        for (Path candidate : COLLECTION_CANDIDATES) {
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }

        throw new IllegalStateException("Unable to locate collection_data.json in expected dataset directories.");
    }

    private static Path stageCollectionInTemporaryDirectory(final Path collectionPath) throws IOException {
        final Path stagingDirectory = Files.createTempDirectory("nexa-collection-");
        Files.copy(collectionPath, stagingDirectory.resolve(collectionPath.getFileName()),
                StandardCopyOption.REPLACE_EXISTING);
        return stagingDirectory;
    }

    private static List<String> extractSearchTokens(final Analyzer analyzer,
                                                    final String fieldName,
                                                    final String text) throws IOException {
        final String safeText = text == null ? "" : text;
        final List<String> tokens = new ArrayList<>();

        try (TokenStream tokenStream = analyzer.tokenStream(fieldName, new StringReader(safeText))) {
            final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();

            while (tokenStream.incrementToken() && tokens.size() < MAX_SEARCH_TOKENS) {
                final String token = termAttribute.toString();
                if (token.isBlank() || tokens.contains(token)) {
                    continue;
                }

                if (!token.contains(" ")) {
                    tokens.add(0, token);
                } else {
                    tokens.add(token);
                }
            }

            tokenStream.end();
        }

        return tokens;
    }

    private static boolean hasExpectation(final List<SearchExpectation> searchExpectations,
                                          final String language,
                                          final String baseFieldName) {
        return searchExpectations.stream()
                .anyMatch(expectation -> language.equals(expectation.language())
                        && baseFieldName.equals(expectation.baseFieldName()));
    }

    private static Map<String, Integer> createLanguageCounter() {
        final Map<String, Integer> counters = new LinkedHashMap<>();
        counters.put(LanguageDetectionUtil.ENGLISH, 0);
        counters.put(LanguageDetectionUtil.FRENCH, 0);
        counters.put(LanguageDetectionUtil.GERMAN, 0);
        counters.put(LanguageDetectionUtil.UNKNOWN, 0);
        return counters;
    }

    private static void deleteRecursively(final Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }

        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to delete temporary path " + path.toAbsolutePath(), e);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to clean temporary directory " + root.toAbsolutePath(), e);
        }
    }

    private static void checkState(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record SearchExpectation(String language,
                                     String baseFieldName,
                                     String localizedFieldName,
                                     int pubkey,
                                     List<String> candidateTokens) {
    }

    private record ExpectedIndexStats(int totalDocuments,
                                      Map<String, Integer> languageCounts,
                                      List<SearchExpectation> searchExpectations) {
    }
}
