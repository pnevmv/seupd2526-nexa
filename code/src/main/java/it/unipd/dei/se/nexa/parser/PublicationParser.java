package it.unipd.dei.se.nexa.parser;

import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.util.Iterator;

/**
 * Parses the publications for the CheckThat! 2026 Task 1 dataset.
 * <p>
 * This class provides an iterator to read through the JSON collection data,
 * allowing for processing of the {@link Publication} objects.
 *
 * @see <a href=
 *      "https://huggingface.co/datasets/sschellhammer/CT26_Task1_SourceRetrievalForScientificWebClaims/blob/main/collection_data.json">CheckThat!
 *      2026 Task 1 Collection Data</a>
 */
public class PublicationParser implements Iterable<Publication> {

    private final Iterator<Publication> iterator;

    /**
     * Create an iterable on the publications of the collection
     * 
     * @param filePath - Path to the collection json file
     */
    public PublicationParser(String filePath) {
        File jsonFile = new File(filePath);
        ObjectMapper objectMapper = new ObjectMapper();
        this.iterator = objectMapper.readerFor(Publication.class).readValues(jsonFile);
    }

    @Override
    public Iterator<Publication> iterator() {
        return this.iterator;
    }

    /**
     * Prints all the publications of the collection.
     * @param args command-line arguments, where {@code args[0]} is the path to the file
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("You must indicate the path in first argument");
            System.exit(1);
        }
        String filePath = args[0];
        PublicationParser parser = new PublicationParser(filePath);
        for (Publication p : parser) {
            System.out.println(p);
            System.out.println("==================================");
        }
    }

    
}
