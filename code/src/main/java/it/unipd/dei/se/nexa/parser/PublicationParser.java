package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;

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
     * @param filePath Path to the collection JSON file
     */
    public PublicationParser(String filePath) {
        try {
            File jsonFile = new File(filePath);
            ObjectMapper objectMapper = new ObjectMapper();
            this.iterator = objectMapper.readerFor(Publication.class).readValues(jsonFile);
        } catch (Exception e) {
            throw new RuntimeException("Error reading publications file", e);
        }
    }

    @Override
    public @NotNull Iterator<Publication> iterator() {
        return this.iterator;
    }
}