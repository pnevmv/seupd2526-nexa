package it.unipd.dei.se.nexa.searcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unipd.dei.se.nexa.analyzer.EnglishAnalyzer;
import it.unipd.dei.se.nexa.parser.Claim;
import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.utility.ConfigManager;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;
import it.unipd.dei.se.nexa.utility.TranslationUtil;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.QueryBuilder;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/**
 * Document searcher.
 *
 * <p>For each claim it parses the text, queries the English-localized title
 * and abstract fields produced by {@link Publication#toLuceneDocument(String)}
 * and writes the top hits to a TREC-formatted run file.
 */
public class Searcher {

    private static final ConfigManager CONFIG = ConfigManager.getGlobalConfig();

    private static final float TITLE_BOOST = 2.0f;
    private static final float ABSTRACT_BOOST = 1.0f;
    private static final int PROGRESS_INTERVAL = 500;

    private final IndexReader reader;
    private final IndexSearcher searcher;
    private final QueryBuilder queryBuilder;
    private final String titleField;
    private final String abstractField;
    private final List<Claim> claims;
    private final String claimsLanguage;
    private final int maxDocsRetrieved;
    private final String runID;
    private final Path runFile;

    public Searcher(final Analyzer analyzer,
                    final Path indexDir,
                    final Path topicsFile,
                    final String runID,
                    final Path runDir,
                    final int maxDocsRetrieved) throws IOException {

        this.reader = DirectoryReader.open(FSDirectory.open(indexDir));
        this.searcher = new IndexSearcher(reader);
        this.searcher.setSimilarity(new BM25Similarity());

        this.titleField = Publication.getLocalizedFieldName(Publication.FIELD_TITLE, "en");
        this.abstractField = Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, "en");
        this.queryBuilder = new QueryBuilder(analyzer);

        this.claims = new ObjectMapper().readValue(topicsFile.toFile(),
                new TypeReference<List<Claim>>() {});
        this.claimsLanguage = inferClaimsLanguage(topicsFile);

        this.runID = runID;
        this.maxDocsRetrieved = maxDocsRetrieved;
        this.runFile = runDir.resolve(runID + ".txt");
    }

    public void search() throws Exception {
        System.out.printf("#### Start searching (%d claims) ####%n", claims.size());
        final boolean translateClaimsToEnglish = TranslationUtil.shouldTranslateClaimsToEnglish();
        final String translationTargetLanguage = TranslationUtil.getTranslationTargetLanguage();
        System.out.println("Translate non-English claims to " + translationTargetLanguage + ": "
                + translateClaimsToEnglish);
        if (translateClaimsToEnglish && claimsLanguage != null) {
            System.out.println("Claims language inferred from topics file: " + claimsLanguage);
        }
        final long start = System.currentTimeMillis();

        try (PrintWriter run = new PrintWriter(Files.newBufferedWriter(
                runFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))) {

            final StoredFields storedFields = reader.storedFields();
            final int total = claims.size();
            int processed = 0;
            int translatedClaims = 0;

            for (Claim claim : claims) {
                processed++;
                if (processed % PROGRESS_INTERVAL == 0 || processed == total) {
                    final long elapsedMs = System.currentTimeMillis() - start;
                    final double rate = processed * 1000.0 / Math.max(1L, elapsedMs);
                    final long etaSec = rate > 0 ? (long) ((total - processed) / rate) : 0L;
                    System.out.printf("  [%5d / %d] %6.1f claims/s, elapsed %3ds, eta %3ds%n",
                            processed, total, rate, elapsedMs / 1000, etaSec);
                }

                String text = claim.getText();
                if (text == null || text.isBlank()) {
                    System.err.printf("Skipping empty claim: index=%d%n", claim.getIndex());
                    continue;
                }

                if (translateClaimsToEnglish) {
                    final String sourceLanguage = claimsLanguage != null
                            ? claimsLanguage
                            : LanguageDetectionUtil.detectClaimLanguage(claim);
                    if (shouldTranslateClaim(sourceLanguage, translationTargetLanguage)) {
                        text = TranslationUtil.translateClaimText(text, sourceLanguage, translationTargetLanguage);
                        translatedClaims++;
                    }
                }

                final Query query = buildQuery(text);
                if (query == null) {
                    System.err.printf("Skipping claim with no analyzable terms: index=%d%n", claim.getIndex());
                    continue;
                }
                final TopDocs topDocs = searcher.search(query, maxDocsRetrieved);
                final ScoreDoc[] hits = topDocs.scoreDocs;

                for (int rank = 0; rank < hits.length; rank++) {
                    final Document doc = storedFields.document(hits[rank].doc);
                    final String pubkey = doc.get(Publication.FIELD_PUBKEY);
                    run.printf(Locale.ENGLISH, "%d Q0 %s %d %f %s%n",
                            claim.getIndex(), pubkey, rank, hits[rank].score, runID);
                }
            }

            if (translateClaimsToEnglish) {
                System.out.printf("Translated %d non-English claim(s) to %s.%n",
                        translatedClaims, translationTargetLanguage);
            }
        } finally {
            reader.close();
        }

        final long elapsed = System.currentTimeMillis() - start;
        System.out.printf("#### Searched %d claim(s) in %d ms -> %s ####%n",
                claims.size(), elapsed, runFile.toAbsolutePath());
    }

    public static void main(String[] args) throws Exception {
        final Path indexDir = Paths.get(requireConfig("indexPath"));
        final Path topicsFile = Paths.get(requireConfig("topics"));
        final String runID = requireConfig("runID");
        final Path runDir = Paths.get(requireConfig("runPath"));
        final int maxDocsRetrieved = CONFIG.getInt("maxDocsRetrieved");

        Files.createDirectories(runDir);

        try (Analyzer analyzer = new EnglishAnalyzer()) {
            new Searcher(analyzer, indexDir, topicsFile, runID, runDir, maxDocsRetrieved).search();
        }
    }

    private Query buildQuery(final String text) {
        final Query titleQuery = queryBuilder.createBooleanQuery(titleField, text, BooleanClause.Occur.SHOULD);
        final Query abstractQuery = queryBuilder.createBooleanQuery(abstractField, text, BooleanClause.Occur.SHOULD);

        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        if (titleQuery != null) {
            builder.add(new BoostQuery(titleQuery, TITLE_BOOST), BooleanClause.Occur.SHOULD);
        }
        if (abstractQuery != null) {
            builder.add(new BoostQuery(abstractQuery, ABSTRACT_BOOST), BooleanClause.Occur.SHOULD);
        }

        final BooleanQuery combined = builder.build();
        return combined.clauses().isEmpty() ? null : combined;
    }

    private static boolean shouldTranslateClaim(final String sourceLanguage, final String targetLanguage) {
        return sourceLanguage != null
                && !sourceLanguage.isBlank()
                && !LanguageDetectionUtil.UNKNOWN.equalsIgnoreCase(sourceLanguage)
                && !sourceLanguage.equalsIgnoreCase(targetLanguage);
    }

    private static String inferClaimsLanguage(final Path topicsFile) {
        if (topicsFile == null || topicsFile.getFileName() == null) {
            return null;
        }

        final String fileName = topicsFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.startsWith(LanguageDetectionUtil.FRENCH + "_")) {
            return LanguageDetectionUtil.FRENCH;
        }
        if (fileName.startsWith(LanguageDetectionUtil.GERMAN + "_")) {
            return LanguageDetectionUtil.GERMAN;
        }
        if (fileName.startsWith(LanguageDetectionUtil.ENGLISH + "_")) {
            return LanguageDetectionUtil.ENGLISH;
        }

        return null;
    }

    private static String requireConfig(final String key) {
        final String value = CONFIG.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return value;
    }
}
