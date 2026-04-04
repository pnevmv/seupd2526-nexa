package it.unipd.dei.se.nexa.parser;

import tools.jackson.databind.ObjectMapper;

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
    public Iterator<Claim> iterator() {
        return this.iterator;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("You must indicate the path in first argument");
            System.exit(1);
        }

        String filePath = args[0];
        ClaimParser parser = new ClaimParser(filePath);

        int i = 0;
        for (Claim c : parser) {
            i++;
            System.out.println(c);
            System.out.println("==================================");
        }

        System.out.println("There are " + i + " claims in the collection");
    }
}