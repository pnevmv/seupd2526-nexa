package it.unipd.dei.se.nexa.indexer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.FSDirectory;

import it.unipd.dei.se.nexa.analyser.PublicationAnalyser;
import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.parser.PublicationParser;

public class PublicationIndexer {

    private final IndexWriter writer;
    private final String collectionPath;
    private int docsCount;
    private final int expectedDocs = 10000;

    public PublicationIndexer(final Analyzer analyzer, final String collectionPath,
            final String indexPath) {
        if (analyzer == null) {
            throw new NullPointerException("Analyzer cannot be null.");
        }
        final IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        final Path indexDir = Paths.get(indexPath);
        if (Files.notExists(indexDir)) {
            try {
                Files.createDirectory(indexDir);
            } catch (Exception e) {
                throw new RuntimeException(String.format("Unable to create directory %s: %s.",
                        indexDir.toAbsolutePath(), e.getMessage()), e);
            }
        }

        this.collectionPath = collectionPath;
        try {
            writer = new IndexWriter(FSDirectory.open(indexDir), iwc);
        } catch (IOException e) {
            throw new IllegalArgumentException(String.format("Unable to create the index writer in directory %s: %s.",
                    indexDir.toAbsolutePath(), e.getMessage()), e);
        }

        this.docsCount = 0;
    }

    /**
     * Indexes the documents.
     *
     * @throws IOException if something goes wrong while indexing.
     */
    public void index() throws IOException {

        System.out.printf("%n#### Start indexing ####%n");
        PublicationParser parser = new PublicationParser(collectionPath);
        Document doc = null;
        for (Publication p : parser) {
            doc = p.toLuceneDocument();
            writer.addDocument(doc);
            docsCount++;
            if (docsCount % 1000 == 0) {
                System.out.printf("%d document(s)  indexed%n", docsCount);
            }
        }
 
        writer.commit();

        writer.close();

        if (docsCount != expectedDocs) {
            System.out.printf("Expected to index %d documents; %d indexed instead.%n", expectedDocs, docsCount);
        }

        System.out.printf("#### Indexing complete ####%n");
    }

   /**
     * Main method of the class. Just for testing purposes.
     *
     * @param args command line arguments.
     * @throws Exception if something goes wrong while indexing.
     */
    public static void main(String[] args) throws Exception {

        if (args.length != 2) {
            throw new IllegalArgumentException("need collection_path and index_path directory as args");
        }

        final Analyzer a = new PublicationAnalyser();
        PublicationIndexer i = new PublicationIndexer(a, args[0], args[1]);

        i.index();
    }

}
