package it.unipd.dei.se.nexa.analyser;

import java.io.IOException;
import java.io.StringReader;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.TokenStream;

import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.parser.PublicationParser;

import org.apache.lucene.analysis.LowerCaseFilter;

public class PublicationAnalyser extends Analyzer {
    @Override
    protected TokenStreamComponents createComponents(String fieldName) {

        Tokenizer tokenizer;
        TokenStream tokens;

        tokenizer = new StandardTokenizer();

        // Converts each character to its lowercase representation
        tokens = new LowerCaseFilter(tokenizer);

        return new TokenStreamComponents(tokenizer, tokens);
    }

    /**
     * Prints the title and the tokenized title of the first publication
     * @param args command-line arguments, where {@code args[0]} is the path to the file
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("You must indicate the path in first argument");
            System.exit(1);
        }
        String filePath = args[0];
        PublicationParser parser = new PublicationParser(filePath);
        Analyzer analyzer = new PublicationAnalyser();
        int i = 0;
        for (Publication p : parser) {
            i += 1;
            System.out.println(p.getTitle());
            System.out.println("==================================");
            TokenStream stream = analyzer.tokenStream("field", new StringReader(p.getTitle()));
            final CharTermAttribute tokenTerm = stream.addAttribute(CharTermAttribute.class);
            try {
                stream.reset();
                while (stream.incrementToken()) {
                    System.out.printf("+ token: %s%n", tokenTerm.toString());
                }
                stream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (i >= 1)
                break;
        }
        analyzer.close();
    }


}
