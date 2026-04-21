package it.unipd.dei.se.nexa.searcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unipd.dei.se.nexa.analyzer.EnglishAnalyzer;
import it.unipd.dei.se.nexa.analyzer.FrenchAnalyzer;
import it.unipd.dei.se.nexa.analyzer.GermanAnalyzer;
import it.unipd.dei.se.nexa.parser.Claim;
import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;
import it.unipd.dei.se.nexa.utility.TranslationUtil;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Searches the publication index using claim datasets as queries.
 *
 * <p>The searcher is adapted to this project:
 * it reads {@code Claim[]} from the CheckThat train/dev JSON files,
 * searches over the localized publication fields produced by
 * {@link it.unipd.dei.se.nexa.index.DirectoryIndexer},
 * and writes a TREC-style run using claim indices as query ids
 * and publication {@code pubkey} values as document ids.</p>
 */
public class Searcher implements AutoCloseable {

    private static final int MAX_TOKENS_FOR_FUZZY = 10;
    private static final int MAX_TOKENS_FOR_PROXIMITY = 12;
    private static final String BUNDLED_TREC_EVAL = "src/main/java/it/unipd/dei/se/nexa/tools/trec_eval";
    private static final Path[] TREC_EVAL_CANDIDATES = {
            Path.of(BUNDLED_TREC_EVAL),
            Path.of("code").resolve(BUNDLED_TREC_EVAL)
    };

    private final Path claimsPath;
    private final String runId;
    private final String datasetLanguage;
    private final SearchOptions options;
    private final IndexReader indexReader;
    private final IndexSearcher indexSearcher;
    private final PrintWriter runWriter;
    private final Path runFile;
    private final Path qrelsFile;
    private final Claim[] claims;
    private final Map<String, Analyzer> analyzers;

    public Searcher(final Path indexPath,
                    final Path claimsPath,
                    final Path runDirectory,
                    final String runId) throws IOException {
        this(indexPath, claimsPath, runDirectory, runId, SearchOptions.defaults());
    }

    public Searcher(final Path indexPath,
                    final Path claimsPath,
                    final Path runDirectory,
                    final String runId,
                    final SearchOptions options) throws IOException {
        Objects.requireNonNull(indexPath, "Index path cannot be null.");
        Objects.requireNonNull(claimsPath, "Claims path cannot be null.");
        Objects.requireNonNull(runDirectory, "Run directory cannot be null.");
        Objects.requireNonNull(runId, "Run identifier cannot be null.");
        Objects.requireNonNull(options, "Search options cannot be null.");

        if (runId.isBlank()) {
            throw new IllegalArgumentException("Run identifier cannot be blank.");
        }
        if (!Files.isDirectory(indexPath) || !Files.isReadable(indexPath)) {
            throw new IllegalArgumentException("Index path must be a readable directory: "
                    + indexPath.toAbsolutePath());
        }
        if (!Files.isRegularFile(claimsPath) || !Files.isReadable(claimsPath)) {
            throw new IllegalArgumentException("Claims path must be a readable JSON file: "
                    + claimsPath.toAbsolutePath());
        }

        Files.createDirectories(runDirectory);
        if (!Files.isWritable(runDirectory)) {
            throw new IllegalArgumentException("Run directory must be writable: "
                    + runDirectory.toAbsolutePath());
        }

        this.claimsPath = claimsPath;
        this.runId = runId;
        this.options = options;
        this.datasetLanguage = detectDatasetLanguage(claimsPath);
        this.claims = new ObjectMapper().readValue(claimsPath.toFile(), Claim[].class);
        this.analyzers = createAnalyzers();
        this.runFile = runDirectory.resolve(runId + ".txt");
        this.qrelsFile = runDirectory.resolve(runId + ".qrels");
        this.indexReader = DirectoryReader.open(FSDirectory.open(indexPath));
        this.indexSearcher = new IndexSearcher(indexReader);
        this.indexSearcher.setSimilarity(new BM25Similarity());
        this.runWriter = new PrintWriter(Files.newBufferedWriter(
                runFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
    }

    public SearchStats search() throws IOException {
        final long start = System.currentTimeMillis();
        final StoredFields storedFields = indexReader.storedFields();
        final EvaluationAccumulator evaluationAccumulator = options.evaluateAgainstGold()
                ? new EvaluationAccumulator()
                : null;
        final boolean translateNonEnglishClaimsToEnglish = TranslationUtil.shouldTranslateClaimsToEnglish();
        final String translationTargetLanguage = TranslationUtil.getTranslationTargetLanguage();

        int skippedClaims = 0;
        long totalRunEntries = 0;

        writeQrelsFile();

        System.out.println("=== SEARCH STARTED ===");
        System.out.println("Claims file: " + claimsPath.toAbsolutePath());
        System.out.println("Claims loaded: " + claims.length);
        System.out.println("Dataset language: " + datasetLanguage);
        System.out.println("Translate non-English claims to " + translationTargetLanguage + ": "
                + translateNonEnglishClaimsToEnglish);
        System.out.println("Run id: " + runId);
        System.out.println("Run file: " + runFile.toAbsolutePath());
        System.out.println("Qrels file: " + qrelsFile.toAbsolutePath());
        System.out.println();

        for (int i = 0; i < claims.length; i++) {
            final Claim claim = claims[i];
            final PreparedClaim preparedClaim = prepareClaim(
                    claim,
                    translateNonEnglishClaimsToEnglish,
                    translationTargetLanguage);
            final Query query = buildQuery(preparedClaim.text(), claim.getIndex(), preparedClaim.language());

            if (query instanceof MatchNoDocsQuery) {
                skippedClaims++;
                if (evaluationAccumulator != null) {
                    evaluationAccumulator.record(claim, 0);
                }
                continue;
            }

            final TopDocs topDocs = indexSearcher.search(query, options.maxDocsRetrieved());
            final ClaimSearchOutcome outcome = writeRunEntries(claim, topDocs.scoreDocs, storedFields);
            totalRunEntries += outcome.writtenEntries();

            if (evaluationAccumulator != null) {
                evaluationAccumulator.record(claim, outcome.relevantRank());
            }

            if ((i + 1) % 100 == 0) {
                System.out.println("Processed claims: " + (i + 1));
            }
        }

        runWriter.flush();

        final long elapsedTime = System.currentTimeMillis() - start;
        final EvaluationMetrics evaluationMetrics = evaluationAccumulator == null
                ? null
                : evaluationAccumulator.toMetrics();
        final TrecEvalResult trecEvalResult = options.runTrecEval()
                ? runTrecEval()
                : null;

        System.out.println();
        System.out.println("=== SEARCH COMPLETED ===");
        System.out.println("Claims searched: " + claims.length);
        System.out.println("Claims skipped: " + skippedClaims);
        System.out.println("Run entries written: " + totalRunEntries);
        System.out.println("Elapsed time (ms): " + elapsedTime);

        if (evaluationMetrics != null) {
            printEvaluationMetrics(evaluationMetrics);
        }
        if (trecEvalResult != null) {
            printTrecEvalResult(trecEvalResult);
        }

        return new SearchStats(
                claimsPath,
                runFile,
                qrelsFile,
                claims.length,
                skippedClaims,
                totalRunEntries,
                elapsedTime,
                evaluationMetrics,
                trecEvalResult
        );
    }

    private Query buildQuery(final String claimText,
                             final int claimIndex,
                             final String language) throws IOException {
        final List<String> originalTokens = analyzeText(claimText, language);
        if (originalTokens.isEmpty()) {
            return new MatchNoDocsQuery("No searchable tokens for claim " + claimIndex);
        }

        final Query originalQuery = buildBaseQuery(originalTokens, language);
        if (!options.usePseudoRelevanceFeedback()) {
            return originalQuery;
        }

        final TopDocs feedbackDocs = indexSearcher.search(originalQuery, options.topDocsForPRF());
        final List<String> expansionTerms = selectExpansionTerms(
                feedbackDocs, language, new HashSet<>(originalTokens));

        if (expansionTerms.isEmpty()) {
            return originalQuery;
        }

        final Query expandedQuery = buildBaseQuery(expansionTerms, language);
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new BoostQuery(originalQuery, options.originalQueryBoost()), BooleanClause.Occur.SHOULD);
        builder.add(new BoostQuery(expandedQuery, options.expansionTermsBoost()), BooleanClause.Occur.SHOULD);
        return builder.build();
    }

    private Query buildBaseQuery(final List<String> tokens, final String language) {
        final String normalizedLanguage = normalizeSearchLanguage(language);
        final String titleField = Publication.getLocalizedFieldName(Publication.FIELD_TITLE, normalizedLanguage);
        final String abstractField = Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, normalizedLanguage);

        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        addFieldClauses(builder, titleField, tokens, options.titleFieldBoost());
        addFieldClauses(builder, abstractField, tokens, options.abstractFieldBoost());

        final BooleanQuery query = builder.build();
        return query.clauses().isEmpty()
                ? new MatchNoDocsQuery("No clauses built for language " + normalizedLanguage)
                : query;
    }

    private void addFieldClauses(final BooleanQuery.Builder target,
                                 final String fieldName,
                                 final List<String> tokens,
                                 final float fieldBoost) {
        if (tokens.isEmpty()) {
            return;
        }

        final BooleanQuery.Builder fieldQuery = new BooleanQuery.Builder();
        for (String token : tokens) {
            fieldQuery.add(new TermQuery(new Term(fieldName, token)), BooleanClause.Occur.SHOULD);

            if (options.useFuzzyQueries() && tokens.size() <= MAX_TOKENS_FOR_FUZZY) {
                final Query fuzzyQuery = new FuzzyQuery(new Term(fieldName, token), options.fuzzyMaxEdits());
                fieldQuery.add(new BoostQuery(fuzzyQuery, options.fuzzyBoost()), BooleanClause.Occur.SHOULD);
            }
        }

        if (options.useProximitySearch()
                && tokens.size() > 1
                && tokens.size() <= MAX_TOKENS_FOR_PROXIMITY) {
            final PhraseQuery.Builder phraseBuilder = new PhraseQuery.Builder();
            phraseBuilder.setSlop(options.proximitySlop());
            for (String token : tokens) {
                phraseBuilder.add(new Term(fieldName, token));
            }

            fieldQuery.add(new BoostQuery(phraseBuilder.build(), options.proximityBoost()),
                    BooleanClause.Occur.SHOULD);
        }

        final Query builtFieldQuery = fieldQuery.build();
        if (!builtFieldQuery.toString().isBlank()) {
            target.add(new BoostQuery(builtFieldQuery, fieldBoost), BooleanClause.Occur.SHOULD);
        }
    }

    private List<String> analyzeText(final String text, final String language) throws IOException {
        final Analyzer analyzer = analyzers.get(normalizeSearchLanguage(language));
        if (analyzer == null || text == null || text.isBlank()) {
            return List.of();
        }

        final String fieldName = Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT,
                normalizeSearchLanguage(language));
        final Set<String> tokens = new LinkedHashSet<>();

        try (TokenStream tokenStream = analyzer.tokenStream(fieldName, text)) {
            final CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();

            while (tokenStream.incrementToken()) {
                final String token = termAttribute.toString();
                if (!token.isBlank()) {
                    tokens.add(token);
                }
            }

            tokenStream.end();
        }

        return new ArrayList<>(tokens);
    }

    private List<String> selectExpansionTerms(final TopDocs topDocs,
                                              final String language,
                                              final Set<String> originalTerms) throws IOException {
        final Map<String, Integer> termFrequencies = new HashMap<>();
        final StoredFields storedFields = indexReader.storedFields();
        final String normalizedLanguage = normalizeSearchLanguage(language);

        for (int i = 0; i < Math.min(options.topDocsForPRF(), topDocs.scoreDocs.length); i++) {
            final Document document = storedFields.document(topDocs.scoreDocs[i].doc);
            final String documentLanguage = normalizeSearchLanguage(document.get(Publication.FIELD_LANGUAGE));
            final String feedbackText = buildFeedbackText(document, documentLanguage);

            for (String token : analyzeText(feedbackText, documentLanguage)) {
                if (originalTerms.contains(token)) {
                    continue;
                }
                if (token.length() < 3 || token.length() > 30) {
                    continue;
                }

                termFrequencies.merge(token, 1, Integer::sum);
            }
        }

        final int totalDocs = indexReader.numDocs();
        final String titleField = Publication.getLocalizedFieldName(Publication.FIELD_TITLE, normalizedLanguage);
        final String abstractField = Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, normalizedLanguage);
        final Map<String, Double> scores = new HashMap<>();

        for (Map.Entry<String, Integer> entry : termFrequencies.entrySet()) {
            final String term = entry.getKey();
            final int tf = entry.getValue();
            final int titleDf = indexReader.docFreq(new Term(titleField, term));
            final int abstractDf = indexReader.docFreq(new Term(abstractField, term));
            final int df = Math.max(titleDf, abstractDf);

            if (df <= 0 || df > totalDocs * 0.5) {
                continue;
            }

            final double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));
            final double score = Math.log1p(tf) * idf;
            scores.put(term, score);
        }

        return scores.entrySet().stream()
                .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                .limit(options.topTermsToAdd())
                .map(Map.Entry::getKey)
                .toList();
    }

    private static String buildFeedbackText(final Document document, final String language) {
        final String title = safeString(document.get(Publication.FIELD_TITLE));
        final String localizedAbstract = safeString(document.get(
                Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, language)));
        return (title + ' ' + localizedAbstract).trim();
    }

    private ClaimSearchOutcome writeRunEntries(final Claim claim,
                                               final ScoreDoc[] scoreDocs,
                                               final StoredFields storedFields) throws IOException {
        int rank = 1;
        long writtenEntries = 0;
        int relevantRank = 0;

        for (ScoreDoc scoreDoc : scoreDocs) {
            final Document document = storedFields.document(scoreDoc.doc);
            final Number pubkey = document.getField(Publication.FIELD_PUBKEY).numericValue();
            if (pubkey == null) {
                continue;
            }

            final int retrievedPubkey = pubkey.intValue();
            if (relevantRank == 0 && retrievedPubkey == claim.getPubkey()) {
                relevantRank = rank;
            }

            runWriter.printf(Locale.ENGLISH, "%d\tQ0\t%d\t%d\t%.6f\t%s%n",
                    claim.getIndex(),
                    retrievedPubkey,
                    rank++,
                    scoreDoc.score,
                    runId);
            writtenEntries++;
        }

        return new ClaimSearchOutcome(writtenEntries, relevantRank);
    }

    private void writeQrelsFile() throws IOException {
        try (PrintWriter qrelsWriter = new PrintWriter(Files.newBufferedWriter(
                qrelsFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))) {
            for (Claim claim : claims) {
                if (claim.getPubkey() <= 0) {
                    continue;
                }

                qrelsWriter.printf(Locale.ENGLISH, "%d\t0\t%d\t1%n",
                        claim.getIndex(),
                        claim.getPubkey());
            }
        }
    }

    private TrecEvalResult runTrecEval() {
        final Path executable = resolveTrecEvalExecutable(options.trecEvalExecutable());
        if (executable == null) {
            return new TrecEvalResult(
                    false,
                    null,
                    -1,
                    "",
                    "trec_eval executable not found. Install it or pass its path as the last CLI argument."
            );
        }

        final List<String> command = List.of(
                executable.toString(),
                "-m", "map",
                "-m", "recip_rank",
                "-m", "P.1",
                "-m", "P.5",
                "-m", "P.10",
                "-m", "recall.1",
                "-m", "recall.5",
                "-m", "recall.10",
                qrelsFile.toString(),
                runFile.toString()
        );

        try {
            final Process process = new ProcessBuilder(command).start();
            final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            final int exitCode = process.waitFor();

            return new TrecEvalResult(true, executable, exitCode, stdout, stderr);
        } catch (IOException e) {
            return new TrecEvalResult(true, executable, -1, "",
                    "Unable to execute trec_eval: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TrecEvalResult(true, executable, -1, "",
                    "trec_eval execution interrupted: " + e.getMessage());
        }
    }

    private void printEvaluationMetrics(final EvaluationMetrics metrics) {
        System.out.println();
        System.out.println("=== BUILT-IN EVALUATION ===");
        System.out.println("Evaluated claims: " + metrics.evaluatedClaims());
        System.out.printf(Locale.ENGLISH, "Hits@1: %d (%.6f)%n", metrics.hitsAt1Count(), metrics.hitsAt1());
        System.out.printf(Locale.ENGLISH, "Hits@5: %d (%.6f)%n", metrics.hitsAt5Count(), metrics.hitsAt5());
        System.out.printf(Locale.ENGLISH, "Hits@10: %d (%.6f)%n", metrics.hitsAt10Count(), metrics.hitsAt10());
        System.out.printf(Locale.ENGLISH, "MRR: %.6f%n", metrics.mrr());
        System.out.printf(Locale.ENGLISH, "MAP: %.6f%n", metrics.map());
    }

    private void printTrecEvalResult(final TrecEvalResult result) {
        System.out.println();
        System.out.println("=== TREC_EVAL ===");
        if (!result.executed()) {
            System.out.println(result.stderr());
            return;
        }

        System.out.println("Executable: " + result.executablePath());
        System.out.println("Exit code: " + result.exitCode());

        if (!result.stdout().isBlank()) {
            System.out.println(result.stdout().trim());
        }
        if (!result.stderr().isBlank()) {
            System.out.println(result.stderr().trim());
        }
    }

    private String resolveClaimLanguage(final Claim claim) {
        if (!LanguageDetectionUtil.UNKNOWN.equals(datasetLanguage)) {
            return datasetLanguage;
        }

        return normalizeSearchLanguage(LanguageDetectionUtil.detectLanguage(claim.getText()));
    }

    private PreparedClaim prepareClaim(final Claim claim,
                                       final boolean translateNonEnglishClaimsToEnglish,
                                       final String translationTargetLanguage) {
        final String claimLanguage = resolveClaimLanguage(claim);
        if (!translateNonEnglishClaimsToEnglish
                || claimLanguage == null
                || claimLanguage.isBlank()
                || LanguageDetectionUtil.UNKNOWN.equalsIgnoreCase(claimLanguage)
                || claimLanguage.equalsIgnoreCase(translationTargetLanguage)) {
            return new PreparedClaim(claim.getText(), claimLanguage);
        }

        return new PreparedClaim(
                TranslationUtil.translateClaimText(claim.getText(), claimLanguage, translationTargetLanguage),
                translationTargetLanguage
        );
    }

    private static String detectDatasetLanguage(final Path claimsPath) {
        final String fileName = claimsPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.startsWith(LanguageDetectionUtil.ENGLISH + "_")) {
            return LanguageDetectionUtil.ENGLISH;
        }
        if (fileName.startsWith(LanguageDetectionUtil.FRENCH + "_")) {
            return LanguageDetectionUtil.FRENCH;
        }
        if (fileName.startsWith(LanguageDetectionUtil.GERMAN + "_")) {
            return LanguageDetectionUtil.GERMAN;
        }

        return LanguageDetectionUtil.UNKNOWN;
    }

    private static String normalizeSearchLanguage(final String language) {
        if (language == null || language.isBlank() || LanguageDetectionUtil.UNKNOWN.equalsIgnoreCase(language)) {
            return LanguageDetectionUtil.ENGLISH;
        }

        return language.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Analyzer> createAnalyzers() {
        final Map<String, Analyzer> languageAnalyzers = new LinkedHashMap<>();
        languageAnalyzers.put(LanguageDetectionUtil.ENGLISH, new EnglishAnalyzer());
        languageAnalyzers.put(LanguageDetectionUtil.FRENCH, new FrenchAnalyzer());
        languageAnalyzers.put(LanguageDetectionUtil.GERMAN, new GermanAnalyzer());
        return languageAnalyzers;
    }

    private static Path resolveTrecEvalExecutable(final String configuredExecutable) {
        if (configuredExecutable == null
                || configuredExecutable.isBlank()
                || "it/unipd/dei/se/nexa/tools/trec_eval".equals(configuredExecutable)
                || BUNDLED_TREC_EVAL.equals(configuredExecutable)) {
            return findBundledTrecEvalExecutable();
        }

        final Path configuredPath = Path.of(configuredExecutable);
        if (configuredExecutable.contains("/") || configuredExecutable.contains("\\") || configuredPath.isAbsolute()) {
            if (Files.isRegularFile(configuredPath) && Files.isExecutable(configuredPath)) {
                return configuredPath;
            }
            return null;
        }

        return findExecutableOnPath(configuredExecutable);
    }

    private static Path findBundledTrecEvalExecutable() {
        for (Path candidate : TREC_EVAL_CANDIDATES) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }

        return findExecutableOnPath("trec_eval");
    }

    private static Path findExecutableOnPath(final String executableName) {
        final String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }

        for (String directory : path.split(java.io.File.pathSeparator)) {
            final Path candidate = Path.of(directory).resolve(executableName);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private static boolean isInteger(final String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String safeString(final String value) {
        return value == null ? "" : value;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;

        runWriter.close();

        try {
            indexReader.close();
        } catch (IOException e) {
            failure = e;
        }

        for (Analyzer analyzer : analyzers.values()) {
            try {
                analyzer.close();
            } catch (Exception e) {
                if (failure == null) {
                    failure = new IOException("Unable to close analyzer.", e);
                }
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) {
            System.out.println("Usage: Searcher <indexDir> <claimsFile> <runDir> <runId> [maxDocs] [trecEvalPath]");
            return;
        }

        final Path indexDir = Path.of(args[0]);
        final Path claimsFile = Path.of(args[1]);
        final Path runDir = Path.of(args[2]);
        final String runId = args[3];

        SearchOptions options = SearchOptions.defaults();
        if (args.length >= 5) {
            if (isInteger(args[4])) {
                options = options.withMaxDocsRetrieved(Integer.parseInt(args[4]));
            } else {
                options = options.withTrecEvalExecutable(args[4]);
            }
        }
        if (args.length == 6) {
            if (!isInteger(args[4])) {
                throw new IllegalArgumentException("The fifth argument must be maxDocs when trecEvalPath is provided.");
            }
            options = options.withTrecEvalExecutable(args[5]);
        }

        try (Searcher searcher = new Searcher(indexDir, claimsFile, runDir, runId, options)) {
            searcher.search();
        }
    }

    public record SearchOptions(int maxDocsRetrieved,
                                boolean useProximitySearch,
                                int proximitySlop,
                                float proximityBoost,
                                boolean usePseudoRelevanceFeedback,
                                int topDocsForPRF,
                                int topTermsToAdd,
                                float originalQueryBoost,
                                float expansionTermsBoost,
                                boolean useFuzzyQueries,
                                int fuzzyMaxEdits,
                                float fuzzyBoost,
                                float titleFieldBoost,
                                float abstractFieldBoost,
                                boolean evaluateAgainstGold,
                                boolean runTrecEval,
                                String trecEvalExecutable) {

        public SearchOptions {
            if (maxDocsRetrieved <= 0) {
                throw new IllegalArgumentException("Maximum retrieved documents must be greater than zero.");
            }
            if (proximitySlop < 0) {
                throw new IllegalArgumentException("Proximity slop cannot be negative.");
            }
            if (topDocsForPRF <= 0) {
                throw new IllegalArgumentException("PRF top documents must be greater than zero.");
            }
            if (topTermsToAdd < 0) {
                throw new IllegalArgumentException("PRF top terms cannot be negative.");
            }
            if (fuzzyMaxEdits < 0 || fuzzyMaxEdits > 2) {
                throw new IllegalArgumentException("Fuzzy max edits must be in the range [0, 2].");
            }
            if (trecEvalExecutable == null || trecEvalExecutable.isBlank()) {
                trecEvalExecutable = BUNDLED_TREC_EVAL;
            }
        }

        public static SearchOptions defaults() {
            return new SearchOptions(
                    100,
                    true,
                    2,
                    1.75f,
                    true,
                    5,
                    5,
                    1.0f,
                    0.35f,
                    true,
                    1,
                    0.20f,
                    2.5f,
                    1.0f,
                    true,
                    true,
                    BUNDLED_TREC_EVAL
            );
        }

        public SearchOptions withMaxDocsRetrieved(final int updatedMaxDocsRetrieved) {
            return new SearchOptions(
                    updatedMaxDocsRetrieved,
                    useProximitySearch,
                    proximitySlop,
                    proximityBoost,
                    usePseudoRelevanceFeedback,
                    topDocsForPRF,
                    topTermsToAdd,
                    originalQueryBoost,
                    expansionTermsBoost,
                    useFuzzyQueries,
                    fuzzyMaxEdits,
                    fuzzyBoost,
                    titleFieldBoost,
                    abstractFieldBoost,
                    evaluateAgainstGold,
                    runTrecEval,
                    trecEvalExecutable
            );
        }

        public SearchOptions withTrecEvalExecutable(final String updatedTrecEvalExecutable) {
            return new SearchOptions(
                    maxDocsRetrieved,
                    useProximitySearch,
                    proximitySlop,
                    proximityBoost,
                    usePseudoRelevanceFeedback,
                    topDocsForPRF,
                    topTermsToAdd,
                    originalQueryBoost,
                    expansionTermsBoost,
                    useFuzzyQueries,
                    fuzzyMaxEdits,
                    fuzzyBoost,
                    titleFieldBoost,
                    abstractFieldBoost,
                    evaluateAgainstGold,
                    runTrecEval,
                    updatedTrecEvalExecutable
            );
        }
    }

    private record ClaimSearchOutcome(long writtenEntries, int relevantRank) {
    }

    private record PreparedClaim(String text, String language) {
    }

    private static final class EvaluationAccumulator {

        private int evaluatedClaims;
        private int hitsAt1Count;
        private int hitsAt5Count;
        private int hitsAt10Count;
        private double reciprocalRankSum;
        private double averagePrecisionSum;

        private void record(final Claim claim, final int relevantRank) {
            if (claim.getPubkey() <= 0) {
                return;
            }

            evaluatedClaims++;
            if (relevantRank == 1) {
                hitsAt1Count++;
            }
            if (relevantRank > 0 && relevantRank <= 5) {
                hitsAt5Count++;
            }
            if (relevantRank > 0 && relevantRank <= 10) {
                hitsAt10Count++;
            }
            if (relevantRank > 0) {
                final double reciprocalRank = 1.0 / relevantRank;
                reciprocalRankSum += reciprocalRank;
                averagePrecisionSum += reciprocalRank;
            }
        }

        private EvaluationMetrics toMetrics() {
            if (evaluatedClaims == 0) {
                return new EvaluationMetrics(0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0);
            }

            return new EvaluationMetrics(
                    evaluatedClaims,
                    hitsAt1Count,
                    hitsAt5Count,
                    hitsAt10Count,
                    (double) hitsAt1Count / evaluatedClaims,
                    (double) hitsAt5Count / evaluatedClaims,
                    (double) hitsAt10Count / evaluatedClaims,
                    reciprocalRankSum / evaluatedClaims,
                    averagePrecisionSum / evaluatedClaims
            );
        }
    }

    public record EvaluationMetrics(int evaluatedClaims,
                                    int hitsAt1Count,
                                    int hitsAt5Count,
                                    int hitsAt10Count,
                                    double hitsAt1,
                                    double hitsAt5,
                                    double hitsAt10,
                                    double mrr,
                                    double map) {
    }

    public record TrecEvalResult(boolean executed,
                                 Path executablePath,
                                 int exitCode,
                                 String stdout,
                                 String stderr) {
    }

    public record SearchStats(Path claimsPath,
                              Path runFile,
                              Path qrelsFile,
                              int totalClaims,
                              int skippedClaims,
                              long writtenRunEntries,
                              long elapsedTimeMillis,
                              EvaluationMetrics evaluationMetrics,
                              TrecEvalResult trecEvalResult) {
    }
}
