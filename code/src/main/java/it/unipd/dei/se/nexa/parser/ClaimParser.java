package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.Iterator;

public class ClaimParser implements Iterable<Claim> {

    private final Iterator<Claim> iterator;

    public ClaimParser(String filePath) {
        try {
            File jsonFile = new File(filePath);
            ObjectMapper objectMapper = new ObjectMapper();

            this.iterator = objectMapper
                    .readerFor(Claim.class)
                    .readValues(jsonFile);
        } catch (Exception e) {
            throw new RuntimeException("Error reading claims file", e);
        }
    }

    @Override
    public @NotNull Iterator<Claim> iterator() {
        return this.iterator;
    }
}