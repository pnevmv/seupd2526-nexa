package it.unipd.dei.se.nexa.manual.analyzer;

import it.unipd.dei.se.nexa.config.analyzer.EnglishAnalyzerConfig;
import it.unipd.dei.se.nexa.config.filters.agnostic.LowerCaseFilterConfig;
import it.unipd.dei.se.nexa.config.filters.en.EnglishDefaultStopFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.en.EnglishMinimalStemFilterConfig;
import it.unipd.dei.se.nexa.config.tokenizer.StandardTokenizerConfig;
import it.unipd.dei.se.nexa.parser.Claim;
import it.unipd.dei.se.nexa.parser.ClaimParser;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.StringReader;
import java.util.List;

public class EnglishClaimAnalyzerTestMain {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Provide path to en_train.json");
            System.exit(1);
        }

        EnglishAnalyzerConfig config = new EnglishAnalyzerConfig(
                List.of(
                        new LowerCaseFilterConfig(),
                        new EnglishDefaultStopFilterConfig()
                ),
                new StandardTokenizerConfig(),
                new EnglishMinimalStemFilterConfig()
        );

        Analyzer analyzer = config.toRuntime();
        ClaimParser parser = new ClaimParser(args[0]);

        int count = 0;
        for (Claim claim : parser) {
            System.out.println("Original: " + claim.getText());
            System.out.println("Tokens:");

            try (TokenStream stream = analyzer.tokenStream("field", new StringReader(claim.getText()))) {
                CharTermAttribute termAttr = stream.addAttribute(CharTermAttribute.class);
                stream.reset();

                while (stream.incrementToken()) {
                    System.out.println(" - " + termAttr.toString());
                }

                stream.end();
            }

            System.out.println("================================");
            count++;
            if (count >= 5) {
                break;
            }
        }

        analyzer.close();
    }
}