package it.unipd.dei.se.nexa.manual.parser;

import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.parser.PublicationParser;

public class PublicationParserTestMain {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("You must indicate the path in first argument");
            System.exit(1);
        }

        String filePath = args[0];
        PublicationParser parser = new PublicationParser(filePath);

        int count = 0;
        for (Publication publication : parser) {
            count++;
            System.out.println(publication);
            System.out.println("==================================");
        }

        System.out.println("There are " + count + " publications in the collection");
    }
}