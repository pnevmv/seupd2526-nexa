package it.unipd.dei.se.nexa.indexer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unipd.dei.se.nexa.analyzer.EnglishAnalyzer;
import it.unipd.dei.se.nexa.analyzer.FrenchAnalyzer;
import it.unipd.dei.se.nexa.analyzer.GermanAnalyzer;
import it.unipd.dei.se.nexa.parser.JsonParser;
import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.utility.ConfigManager;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


public class DirectoryIndexer {

    private final Path docsDir;
    private final IndexWriter writer;
    private static final ConfigManager config = ConfigManager.getGlobalConfig();


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


    public void index() throws IOException {
        int count = 0;
        long start = System.currentTimeMillis();

        System.out.println("Starting the indexing" + docsDir.toAbsolutePath());

        try (Stream<Path> stream = Files.walk(docsDir)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))::iterator) {

                System.out.println("Indexing: " + file.getFileName());
                try (Reader reader = Files.newBufferedReader(file)) {
                    JsonParser parser = new JsonParser(reader);

                    for (Publication pub : parser) {
                        if (pub != null) {
                            final String detectedLanguage = LanguageDetectionUtil.detectPublicationLanguage(pub);
                            try {
                                String textToVectorize = safeString(pub.getTitle()) + " " + safeString(pub.getAbstract());

                                float[] vector = getVector(textToVectorize);
                                pub.setEmbedding(vector);

                            } catch (Exception e) {
                                System.err.println("Errore vettorizzazione per id: " + pub.getPubkey() + ": " + e.getMessage());
                            }

                            writer.addDocument(pub.toLuceneDocument(detectedLanguage));
                            System.out.println("Indexed document: " + count + " with id: " + pub.getPubkey()
                                    + " [" + detectedLanguage + "]");
                            count++;
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
    }

    private float[] getVector(String text) throws Exception {

        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> payload = Map.of("texts", List.of(text));
        String jsonBody = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8080/process"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Errore dal server BERT: " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode vectorNode = root.path("embeddings").get(0);

        float[] vector = new float[vectorNode.size()];
        for (int i = 0; i < vectorNode.size(); i++) {
            vector[i] = (float) vectorNode.get(i).asDouble();
        }
        return vector;
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

    private static String safeString(final String value) {
        return value == null ? "" : value;
    }


    public static void main(String[] args) {
        final Path inputPath;
        if (args.length >= 1) {
            inputPath = Paths.get(args[0]);
        } else {
            final String configuredCollectionPath = config.getString("collectionPath");
            if (configuredCollectionPath == null || configuredCollectionPath.isBlank()) {
                System.out.println("Usage: DirectoryIndexer <collectionJsonOrDir> [indexDir]");
                return;
            }
            inputPath = Paths.get(configuredCollectionPath);
        }

        final Path indexPath = args.length >= 2
                ? Paths.get(args[1])
                : Path.of(config.getString("indexPath"));

        try (Analyzer analyzer = buildLanguageAwareAnalyzer()) {
            System.out.println("DirectoryIndexer...");
            DirectoryIndexer indexer = new DirectoryIndexer(inputPath, indexPath, analyzer);
            indexer.index();
        } catch (IOException e) {
            throw new IllegalStateException("Error I/O handle while indexing", e);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
        }
    }
}
