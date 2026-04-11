package it.unipd.dei.se.nexa.manual.parser;

import it.unipd.dei.se.nexa.parser.Claim;
import it.unipd.dei.se.nexa.parser.ClaimParser;

public class ClaimParserTestMain {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("You must indicate the path in first argument");
            System.exit(1);
        }

        String filePath = args[0];
        ClaimParser parser = new ClaimParser(filePath);

        int count = 0;
        for (Claim claim : parser) {
            count++;
            System.out.println(claim);
            System.out.println("==================================");
        }

        System.out.println("There are " + count + " claims in the collection");
    }
}