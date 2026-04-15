package it.unipd.dei.se.nexa.index;

import it.unipd.dei.se.nexa.analyzer.EnglishAnalyzer;
import it.unipd.dei.se.nexa.analyzer.FrenchAnalyzer;
import it.unipd.dei.se.nexa.analyzer.GermanAnalyzer;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;
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

    private final Path docsDir;
    private final IndexWriter writer;


    public DirectoryIndexer(Path docsDir, Path indexPath, Analyzer analyzer) throws IOException {
        if (!Files.isReadable(docsDir)) {
            throw new IllegalArgumentException("Error with folder: " + docsDir.toAbsolutePath());
        }
        this.docsDir = docsDir;

        Directory dir = FSDirectory.open(indexPath);
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        this.writer = new IndexWriter(dir, iwc);
    }

    public DirectoryIndexer(Path docsDir, Path indexPath) throws IOException {
        this(docsDir, indexPath, buildLanguageAwareAnalyzer());
    }

    public void index() throws IOException {
        int count = 0;
        long start = System.currentTimeMillis();
        Map<String, Integer> languageCounts = createLanguageCounter();

        System.out.println("Starting the indexing" + docsDir.toAbsolutePath());

        try (Stream<Path> stream = Files.walk(docsDir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))::iterator) {

                System.out.println("Indexing: " + file.getFileName());
                try (Reader reader = Files.newBufferedReader(file)) {
                    JsonParser parser = new JsonParser(reader);

                    for (Publication pub : parser) {
                        if (pub != null) {
                            String detectedLanguage = LanguageDetectionUtil.detectPublicationLanguage(pub);
                            writer.addDocument(pub.toLuceneDocument(detectedLanguage));
                            languageCounts.merge(detectedLanguage, 1, Integer::sum);
                            count++;
                            if (count % 10000 == 0) {
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
        System.out.println("Detected language distribution: " + languageCounts);
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

    public static void main(String[] args) {
        Path inputDir = Paths.get("C:\\Users\\andre\\OneDrive\\Desktop\\desktop\\SEARCH");//change directory to test
        Path indexDir = Paths.get("target/lucene-index");

        try {
            System.out.println("DirectoryIndexer...");
            DirectoryIndexer indexer = new DirectoryIndexer(inputDir, indexDir);
            indexer.index();
        } catch (IOException e) {
            throw new IllegalStateException("Error I/O handle while indexing", e);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
        }
    }
}
