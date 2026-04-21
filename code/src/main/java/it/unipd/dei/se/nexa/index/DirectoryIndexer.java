package it.unipd.dei.se.nexa.index;

import it.unipd.dei.se.nexa.analyzer.EnglishAnalyzer;
import it.unipd.dei.se.nexa.analyzer.FrenchAnalyzer;
import it.unipd.dei.se.nexa.analyzer.GermanAnalyzer;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;
import it.unipd.dei.se.nexa.utility.TranslationUtil;
import it.unipd.dei.se.nexa.parser.JsonParser;
import it.unipd.dei.se.nexa.parser.Publication;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;


public class DirectoryIndexer {

    private static final int DEFAULT_PROGRESS_REPORT_INTERVAL = 10000;

    private final Path docsDir;
    private final IndexWriter writer;
    private final int progressReportInterval;


    public DirectoryIndexer(Path docsDir, Path indexPath, Analyzer analyzer) throws IOException {
        this(docsDir, indexPath, analyzer, DEFAULT_PROGRESS_REPORT_INTERVAL);
    }

    public DirectoryIndexer(Path docsDir, Path indexPath, Analyzer analyzer, int progressReportInterval)
            throws IOException {
        if (!Files.isReadable(docsDir)) {
            throw new IllegalArgumentException("Error with folder: " + docsDir.toAbsolutePath());
        }
        if (progressReportInterval <= 0) {
            throw new IllegalArgumentException("Progress report interval must be greater than zero.");
        }
        this.docsDir = docsDir;
        this.progressReportInterval = progressReportInterval;

        Directory dir = FSDirectory.open(indexPath);
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        this.writer = new IndexWriter(dir, iwc);
    }

    public DirectoryIndexer(Path docsDir, Path indexPath) throws IOException {
        this(docsDir, indexPath, buildLanguageAwareAnalyzer());
    }

    public DirectoryIndexer(Path docsDir, Path indexPath, int progressReportInterval) throws IOException {
        this(docsDir, indexPath, buildLanguageAwareAnalyzer(), progressReportInterval);
    }

    public void index() throws IOException {
        int count = 0;
        long start = System.currentTimeMillis();
        Map<String, Integer> languageCounts = createLanguageCounter();
        final boolean translateNonEnglishToEnglish = TranslationUtil.shouldTranslatePublicationsToEnglish();
        final String translationTargetLanguage = TranslationUtil.getTranslationTargetLanguage();

        System.out.println("Starting the indexing" + docsDir.toAbsolutePath());
        System.out.println("Translate non-English publications to " + translationTargetLanguage + ": "
                + translateNonEnglishToEnglish);

        try (Stream<Path> stream = Files.walk(docsDir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))::iterator) {

                System.out.println("Indexing: " + file.getFileName());
                try (Reader reader = Files.newBufferedReader(file)) {
                    JsonParser parser = new JsonParser(reader);

                    for (Publication pub : parser) {
                        if (isIndexablePublication(pub)) {
                            String detectedLanguage = LanguageDetectionUtil.detectPublicationLanguage(pub);
                            Publication publicationToIndex = pub;
                            String indexingLanguage = resolveIndexingLanguage(detectedLanguage);

                            if (translateNonEnglishToEnglish
                                    && shouldTranslateToEnglish(indexingLanguage, translationTargetLanguage)) {
                                publicationToIndex = TranslationUtil.translatePublication(
                                        pub,
                                        indexingLanguage,
                                        translationTargetLanguage);
                                indexingLanguage = translationTargetLanguage;
                            }

                            writer.addDocument(publicationToIndex.toLuceneDocument(indexingLanguage));
                            languageCounts.merge(indexingLanguage, 1, Integer::sum);
                            count++;
                            if (count % progressReportInterval == 0) {
                                System.out.printf("-> Process %d pubblications%n", count);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Error while parsing JSON file  " + file.getFileName() + ": " + e.getMessage());
                }
            }
        }

        writer.commit();
        writer.close();

        long end = System.currentTimeMillis();
        System.out.printf("\nIndexing complete. %d documents in target in %d ms.%n", count, (end - start));
        System.out.println("Indexed language distribution: " + languageCounts);
    }

    static String resolveIndexingLanguage(final String detectedLanguage) {
        if (detectedLanguage == null || detectedLanguage.isBlank()) {
            return LanguageDetectionUtil.ENGLISH;
        }

        return LanguageDetectionUtil.UNKNOWN.equalsIgnoreCase(detectedLanguage)
                ? LanguageDetectionUtil.ENGLISH
                : detectedLanguage.toLowerCase();
    }

    private static Analyzer buildLanguageAwareAnalyzer() {
        Map<String, Analyzer> fieldAnalyzers = new LinkedHashMap<>();

        fieldAnalyzers.put(Publication.getLocalizedFieldName(Publication.FIELD_TITLE, LanguageDetectionUtil.ENGLISH),
                new EnglishAnalyzer());
        fieldAnalyzers.put(Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, LanguageDetectionUtil.ENGLISH),
                new EnglishAnalyzer());

        fieldAnalyzers.put(Publication.getLocalizedFieldName(Publication.FIELD_TITLE, LanguageDetectionUtil.FRENCH),
                new FrenchAnalyzer());
        fieldAnalyzers.put(Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, LanguageDetectionUtil.FRENCH),
                new FrenchAnalyzer());

        fieldAnalyzers.put(Publication.getLocalizedFieldName(Publication.FIELD_TITLE, LanguageDetectionUtil.GERMAN),
                new GermanAnalyzer());
        fieldAnalyzers.put(Publication.getLocalizedFieldName(Publication.FIELD_ABSTRACT, LanguageDetectionUtil.GERMAN),
                new GermanAnalyzer());

        return new PerFieldAnalyzerWrapper(new StandardAnalyzer(), fieldAnalyzers);
    }

    private static Map<String, Integer> createLanguageCounter() {
        Map<String, Integer> counters = new LinkedHashMap<>();
        counters.put(LanguageDetectionUtil.ENGLISH, 0);
        counters.put(LanguageDetectionUtil.FRENCH, 0);
        counters.put(LanguageDetectionUtil.GERMAN, 0);
        counters.put(LanguageDetectionUtil.UNKNOWN, 0);
        return counters;
    }

    private static boolean isIndexablePublication(final Publication publication) {
        if (publication == null) {
            return false;
        }

        final String title = publication.getTitle();
        final String abstractText = publication.getAbstract();

        final boolean hasTitle = title != null && !title.isBlank();
        final boolean hasAbstract = abstractText != null && !abstractText.isBlank() && !"#".equals(abstractText);

        return hasTitle || hasAbstract;
    }

    private static boolean shouldTranslateToEnglish(final String sourceLanguage, final String targetLanguage) {
        return sourceLanguage != null
                && !sourceLanguage.isBlank()
                && !LanguageDetectionUtil.UNKNOWN.equalsIgnoreCase(sourceLanguage)
                && !sourceLanguage.equalsIgnoreCase(targetLanguage);
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("Usage: DirectoryIndexer <docsDir> <indexDir> [progressReportInterval]");
            return;
        }

        final Path inputDir = Paths.get(args[0]);
        final Path indexDir = Paths.get(args[1]);
        final int progressReportInterval = args.length == 3
                ? Integer.parseInt(args[2])
                : DEFAULT_PROGRESS_REPORT_INTERVAL;

        try {
            System.out.println("DirectoryIndexer...");
            DirectoryIndexer indexer = new DirectoryIndexer(inputDir, indexDir, progressReportInterval);
            indexer.index();
        } catch (IOException e) {
            throw new IllegalStateException("Error I/O handle while indexing", e);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
        }
    }
}
