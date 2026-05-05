package it.unipd.dei.se.nexa.searcher;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unipd.dei.se.nexa.analyzer.EnglishAnalyzer;
import it.unipd.dei.se.nexa.parser.Claim;
import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.utility.ConfigManager;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;
import it.unipd.dei.se.nexa.utility.EmbeddingService;
import it.unipd.dei.se.nexa.utility.RerankService;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.KnnFloatVectorQuery;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Document searcher.
 *
 * <p>
 * For each claim it parses the text, queries the English-localized title
 * and abstract fields produced by {@link Publication#toLuceneDocument(String)}
 * and writes the top hits to a TREC-formatted run file.
 */
public class Searcher {

    private static final ConfigManager CONFIG = ConfigManager.getGlobalConfig();

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
    private final String searchMode;
    private final EmbeddingService embeddingService;
    private final RerankService rerankService;
    private final Analyzer analyzer;

    public Searcher(final Analyzer analyzer,
                    final Path indexDir,
                    final Path topicsFile,
                    final String runID,
                    final Path runDir,
                    final int maxDocsRetrieved) throws IOException {

        this.analyzer = analyzer;
        this.reader = DirectoryReader.open(FSDirectory.open(indexDir));
        this.searcher = new IndexSearcher(reader);

        Double k1Opt = CONFIG.getDouble("bm25_k1");
        float k1 = k1Opt != null ? k1Opt.floatValue() : 1.2f;

        Double bOpt = CONFIG.getDouble("bm25_b");
        float b = bOpt != null ? bOpt.floatValue() : 0.75f;

        this.searcher.setSimilarity(new BM25Similarity(k1, b));

        this.titleField = Publication.getLocalizedFieldName(Publication.FIELD_TITLE, "en");
        this.abstractField = Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, "en");
        this.queryBuilder = new QueryBuilder(analyzer);

        this.claims = new ObjectMapper().readValue(topicsFile.toFile(),
                new TypeReference<>() {
                });
        this.claimsLanguage = inferClaimsLanguage(topicsFile);

        this.runID = runID;
        this.maxDocsRetrieved = maxDocsRetrieved;
        this.runFile = runDir.resolve(runID + ".txt");

        final String modeStr = CONFIG.getString("searchMode");
        this.searchMode = modeStr != null && !modeStr.isBlank() ? modeStr.toLowerCase(Locale.ROOT) : "lexical";

        if ("semantic".equals(searchMode) || "hybrid".equals(searchMode)) {
            this.embeddingService = new EmbeddingService(requireConfig("embeddingsServiceUrl"));
        } else {
            this.embeddingService = null;
        }

        final Boolean reRank = CONFIG.getBool("reRank");
        if (reRank != null && reRank) {
            this.rerankService = new RerankService(requireConfig("reRankServiceUrl"));
        } else {
            this.rerankService = null;
        }
    }

    public void search() throws Exception {
        System.out.printf("#### Start searching (%d claims) ####%n", claims.size());
        final ClaimQueryProcessor processor = new ClaimQueryProcessor(claimsLanguage);
        System.out.println("Translate non-English claims to " + processor.translationTargetLanguage() + ": "
                + processor.translatesClaimsToEnglish());
        if (processor.translatesClaimsToEnglish() && claimsLanguage != null) {
            System.out.println("Claims language inferred from topics file: " + claimsLanguage);
        }
        if (processor.translatesClaimsToEnglish()) {
            System.out.println(
                    "[WARNING] Claim translation is enabled — make sure the translation server is running first:");
            System.out.println("          bash code/scripts/run_translategemma_server.sh");
        }
        final long start = System.currentTimeMillis();

        try (RunWriter run = new RunWriter(runFile, runID)) {

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

                final ProcessedClaimQuery processedClaim = processor.process(claim);
                String text = processedClaim.text();
                if (text == null || text.isBlank()) {
                    System.err.printf("Skipping empty claim: index=%d%n", claim.getIndex());
                    continue;
                }
                if (processedClaim.translated()) {
                    translatedClaims++;
                }

                ScoreDoc[] hits = new ScoreDoc[0];
                Query lexicalQuery = null;
                Query semanticQuery = null;

                if ("lexical".equals(searchMode) || "hybrid".equals(searchMode)) {
                    lexicalQuery = buildLexicalQuery(text);

                    // PRF
                    Boolean prfEnabled = CONFIG.getBool("prf");
                    if (prfEnabled != null && prfEnabled && lexicalQuery != null) {
                        int prfDocs = CONFIG.getInt("numOfDocsToRetrieveForPrf") != null
                                ? CONFIG.getInt("numOfDocsToRetrieveForPrf")
                                : 3;
                        int prfTerms = CONFIG.getInt("numOfTokenFromPrf") != null ? CONFIG.getInt("numOfTokenFromPrf")
                                : 5;

                        TopDocs initialDocs = searcher.search(lexicalQuery, prfDocs);
                        if (initialDocs.scoreDocs.length > 0) {
                            java.util.List<String> extraTerms = extractPrfTerms(initialDocs, prfTerms);
                            if (!extraTerms.isEmpty()) {
                                BooleanQuery.Builder expandedBuilder = new BooleanQuery.Builder();
                                expandedBuilder.add(lexicalQuery, BooleanClause.Occur.SHOULD);
                                for (String term : extraTerms) {
                                    expandedBuilder.add(
                                            new BoostQuery(new org.apache.lucene.search.TermQuery(
                                                    new org.apache.lucene.index.Term(titleField, term)), 0.3f),
                                            BooleanClause.Occur.SHOULD);
                                    expandedBuilder
                                            .add(new BoostQuery(
                                                    new org.apache.lucene.search.TermQuery(
                                                            new org.apache.lucene.index.Term(abstractField, term)),
                                                    0.3f), BooleanClause.Occur.SHOULD);
                                }
                                lexicalQuery = expandedBuilder.build();
                            }
                        }
                    }

                }

                if ("semantic".equals(searchMode) || "hybrid".equals(searchMode)) {
                    float[] queryEmbedding = null;
                    if (embeddingService != null) {
                        queryEmbedding = embeddingService.getEmbedding(text);
                    }
                    semanticQuery = buildSemanticQuery(queryEmbedding);
                }

                if ("hybrid".equals(searchMode)) {
                    TopDocs lexDocs = lexicalQuery != null ? searcher.search(lexicalQuery, maxDocsRetrieved) : null;
                    TopDocs semDocs = semanticQuery != null ? searcher.search(semanticQuery, maxDocsRetrieved) : null;
                    hits = combineWithRRF(lexDocs, semDocs, maxDocsRetrieved);
                } else if ("semantic".equals(searchMode)) {
                    if (semanticQuery != null) {
                        hits = searcher.search(semanticQuery, maxDocsRetrieved).scoreDocs;
                    }
                } else {
                    if (lexicalQuery != null) {
                        hits = searcher.search(lexicalQuery, maxDocsRetrieved).scoreDocs;
                    }
                }

                if (hits == null || hits.length == 0) {
                    System.err.printf("Skipping claim with no analyzable terms or vectors: index=%d%n",
                            claim.getIndex());
                    continue;
                }

                if (rerankService != null) {
                    int numToRerank = Math.min(hits.length, CONFIG.getInt("numOfDocsToRerank"));
                    ScoreDoc[] topHits = new ScoreDoc[numToRerank];
                    System.arraycopy(hits, 0, topHits, 0, numToRerank);

                    java.util.List<String> docTexts = new java.util.ArrayList<>();
                    for (ScoreDoc hit : topHits) {
                        Document doc = storedFields.document(hit.doc);
                        String title = doc.get(titleField);
                        String abs = doc.get(abstractField);
                        String content = (title != null ? title : "") + " " + (abs != null ? abs : "");
                        docTexts.add(content.trim());
                    }

                    float[] newScores = rerankService.rerank(text, docTexts);

                    // 1. Min-Max normalization per gli score originali
                    float minOrig = Float.MAX_VALUE;
                    float maxOrig = -Float.MAX_VALUE;
                    for (ScoreDoc hit : topHits) {
                        if (hit.score < minOrig)
                            minOrig = hit.score;
                        if (hit.score > maxOrig)
                            maxOrig = hit.score;
                    }
                    if (maxOrig > minOrig) {
                        for (ScoreDoc hit : topHits) {
                            hit.score = (hit.score - minOrig) / (maxOrig - minOrig);
                        }
                    }

                    // 2. Min-Max normalization per i nuovi score (Reranker)
                    float minNew = Float.MAX_VALUE;
                    float maxNew = -Float.MAX_VALUE;
                    for (float s : newScores) {
                        if (s < minNew)
                            minNew = s;
                        if (s > maxNew)
                            maxNew = s;
                    }
                    if (maxNew > minNew) {
                        for (int i = 0; i < newScores.length; i++) {
                            newScores[i] = (newScores[i] - minNew) / (maxNew - minNew);
                        }
                    }

                    // 3. Interpolazione score
                    Double alphaOpt = CONFIG.getDouble("rerankAlpha");
                    float alpha = alphaOpt != null ? alphaOpt.floatValue() : 0.6f;

                    for (int i = 0; i < topHits.length; i++) {
                        if (i < newScores.length) {
                            topHits[i].score = (1.0f - alpha) * topHits[i].score + alpha * newScores[i];
                        }
                    }

                    java.util.Arrays.sort(topHits, (a, b) -> Float.compare(b.score, a.score));
                    System.arraycopy(topHits, 0, hits, 0, topHits.length);
                }

                for (int rank = 0; rank < hits.length; rank++) {
                    final Document doc = storedFields.document(hits[rank].doc);
                    final String pubkey = doc.get(Publication.FIELD_PUBKEY);
                    run.write(claim.getIndex(), pubkey, rank, hits[rank].score);
                }
            }

            if (processor.translatesClaimsToEnglish()) {
                System.out.printf("Translated %d non-English claim(s) to %s.%n",
                        translatedClaims, processor.translationTargetLanguage());
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

    private Query buildLexicalQuery(final String text) {
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        final Query titleQuery = queryBuilder.createBooleanQuery(titleField, text, BooleanClause.Occur.SHOULD);
        final Query abstractQuery = queryBuilder.createBooleanQuery(abstractField, text, BooleanClause.Occur.SHOULD);

        Double titleBoostOpt = CONFIG.getDouble("titleBoost");
        float titleBoost = titleBoostOpt != null ? titleBoostOpt.floatValue() : 2.0f;
        Double absBoostOpt = CONFIG.getDouble("abstractBoost");
        float abstractBoost = absBoostOpt != null ? absBoostOpt.floatValue() : 1.0f;

        if (titleQuery != null) {
            builder.add(new BoostQuery(titleQuery, titleBoost), BooleanClause.Occur.SHOULD);
        }
        if (abstractQuery != null) {
            builder.add(new BoostQuery(abstractQuery, abstractBoost), BooleanClause.Occur.SHOULD);
        }

        Double phraseBoostOpt = CONFIG.getDouble("phraseBoost");
        if (phraseBoostOpt != null && phraseBoostOpt > 0) {
            Query titlePhrase = queryBuilder.createPhraseQuery(titleField, text);
            if (titlePhrase != null) {
                builder.add(new BoostQuery(titlePhrase, phraseBoostOpt.floatValue()), BooleanClause.Occur.SHOULD);
            }
            Query abstractPhrase = queryBuilder.createPhraseQuery(abstractField, text);
            if (abstractPhrase != null) {
                builder.add(new BoostQuery(abstractPhrase, phraseBoostOpt.floatValue()), BooleanClause.Occur.SHOULD);
            }
        }

        Double fuzzyBoostOpt = CONFIG.getDouble("fuzzyBoost");
        if (fuzzyBoostOpt != null && fuzzyBoostOpt > 0) {
            String[] tokens = text.split("\\W+");
            for (String token : tokens) {
                if (token.length() > 3) {
                    builder.add(
                            new BoostQuery(
                                    new org.apache.lucene.search.FuzzyQuery(
                                            new org.apache.lucene.index.Term(titleField, token), 1),
                                    fuzzyBoostOpt.floatValue()),
                            BooleanClause.Occur.SHOULD);
                    builder.add(new BoostQuery(
                            new org.apache.lucene.search.FuzzyQuery(
                                    new org.apache.lucene.index.Term(abstractField, token), 1),
                            fuzzyBoostOpt.floatValue()), BooleanClause.Occur.SHOULD);
                }
            }
        }

        final BooleanQuery combined = builder.build();
        return combined.clauses().isEmpty() ? null : combined;
    }

    private Query buildSemanticQuery(final float[] queryEmbedding) {
        if (queryEmbedding != null && queryEmbedding.length > 0) {
            return new KnnFloatVectorQuery("pub_vector", queryEmbedding, maxDocsRetrieved);
        }
        return null;
    }

    private ScoreDoc[] combineWithRRF(TopDocs lexicalDocs, TopDocs semanticDocs, int maxResults) {
        java.util.Map<Integer, Double> rrfScores = calculateRrfScores(lexicalDocs, semanticDocs);

        java.util.List<java.util.Map.Entry<Integer, Double>> sorted = new java.util.ArrayList<>(rrfScores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        ScoreDoc[] finalHits = new ScoreDoc[Math.min(maxResults, sorted.size())];
        for (int i = 0; i < finalHits.length; i++) {
            java.util.Map.Entry<Integer, Double> entry = sorted.get(i);
            finalHits[i] = new ScoreDoc(entry.getKey(), entry.getValue().floatValue());
        }
        return finalHits;
    }

    private java.util.Map<Integer, Double> calculateRrfScores(TopDocs lexicalDocs, TopDocs semanticDocs) {
        java.util.Map<Integer, Double> rrfScores = new java.util.HashMap<>();

        Double kOpt = CONFIG.getDouble("rrfK");
        int k = kOpt != null ? kOpt.intValue() : 60;

        Double alphaOpt = CONFIG.getDouble("rrfAlpha");
        double alpha = alphaOpt != null ? alphaOpt : 0.5;

        if (lexicalDocs != null && lexicalDocs.scoreDocs != null) {
            for (int i = 0; i < lexicalDocs.scoreDocs.length; i++) {
                int docId = lexicalDocs.scoreDocs[i].doc;
                double score = alpha * (1.0 / (k + i + 1)); // alpha weights lexical contribution
                rrfScores.put(docId, rrfScores.getOrDefault(docId, 0.0) + score);
            }
        }

        if (semanticDocs != null && semanticDocs.scoreDocs != null) {
            for (int i = 0; i < semanticDocs.scoreDocs.length; i++) {
                int docId = semanticDocs.scoreDocs[i].doc;
                double score = (1.0 - alpha) * (1.0 / (k + i + 1)); // (1-alpha) weights semantic contribution
                rrfScores.put(docId, rrfScores.getOrDefault(docId, 0.0) + score);
            }
        }

        return rrfScores;
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

    private java.util.List<String> extractPrfTerms(TopDocs topDocs, int numTerms) throws IOException {
        java.util.Map<String, Integer> termFreq = new java.util.HashMap<>();
        org.apache.lucene.index.StoredFields storedFields = reader.storedFields();

        for (ScoreDoc hit : topDocs.scoreDocs) {
            Document doc = storedFields.document(hit.doc);
            String title = doc.get(titleField);
            String abs = doc.get(abstractField);
            String content = (title != null ? title : "") + " " + (abs != null ? abs : "");

            try (org.apache.lucene.analysis.TokenStream stream = analyzer.tokenStream("dummy",
                    new java.io.StringReader(content))) {
                stream.reset();
                org.apache.lucene.analysis.tokenattributes.CharTermAttribute termAtt = stream
                        .addAttribute(org.apache.lucene.analysis.tokenattributes.CharTermAttribute.class);
                while (stream.incrementToken()) {
                    String term = termAtt.toString();
                    if (term.length() > 2 && !term.matches(".*\\d.*")) { // ignora numeri e parole corte
                        termFreq.put(term, termFreq.getOrDefault(term, 0) + 1);
                    }
                }
                stream.end();
            }
        }

        return termFreq.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(numTerms)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList());
    }

    private static String requireConfig(final String key) {
        final String value = CONFIG.getString(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required config key: " + key);
        }
        return value;
    }
}
