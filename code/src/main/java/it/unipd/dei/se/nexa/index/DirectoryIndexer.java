package it.unipd.dei.se.nexa.index;

import it.unipd.dei.se.nexa.parser.JsonParser;
import it.unipd.dei.se.nexa.parser.Publication;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.*;
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
                            writer.addDocument(pub.toLuceneDocument());
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
    }

    private float[] getVector(String text) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> payload = Map.of("texts", List.of(text));
        String jsonBody = mapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8000/embed"))
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


    public static void main(String[] args) {
        Path inputDir = Paths.get("C:\\Users\\andre\\OneDrive\\Desktop\\desktop\\SEARCH");//change directory to test
        Path indexDir = Paths.get("target/lucene-index");

        try (Analyzer analyzer = new StandardAnalyzer()) {
            System.out.println("DirectoryIndexer...");
            DirectoryIndexer indexer = new DirectoryIndexer(inputDir, indexDir, analyzer);
            indexer.index();
        } catch (IOException e) {
            throw new IllegalStateException("Error I/O handle while indexing", e);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
        }
    }
}
