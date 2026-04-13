package it.unipd.dei.se.nexa.manual.preprocessor;

import it.unipd.dei.se.nexa.config.analyzer.EnglishAnalyzerConfig;
import it.unipd.dei.se.nexa.config.filters.agnostic.LowerCaseFilterConfig;
import it.unipd.dei.se.nexa.config.filters.en.EnglishDefaultStopFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.en.EnglishMinimalStemFilterConfig;
import it.unipd.dei.se.nexa.config.tokenizer.StandardTokenizerConfig;
import it.unipd.dei.se.nexa.parser.Claim;
import it.unipd.dei.se.nexa.parser.ClaimParser;
import it.unipd.dei.se.nexa.preprocessor.PreProcessor;
import it.unipd.dei.se.nexa.preprocessor.RegexPreProcessor;
import it.unipd.dei.se.nexa.preprocessor.UnicodeNormalizerPreProcessor;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.StringReader;
import java.util.List;

public class PreProcessorTestMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Provide path to en_train.json");
            System.exit(1);
        }

        List<PreProcessor> pipeline = List.of(
                new UnicodeNormalizerPreProcessor(),
                RegexPreProcessor.twitterMentionStripper()
        );

        Analyzer analyzer = new EnglishAnalyzerConfig(
                List.of(new LowerCaseFilterConfig(), new EnglishDefaultStopFilterConfig()),
                new StandardTokenizerConfig(),
                new EnglishMinimalStemFilterConfig()
        ).toRuntime();

        ClaimParser parser = new ClaimParser(args[0]);
        int count = 0;

        for (Claim claim : parser) {
            String raw = claim.getText();

            String cleaned = raw;
            for (PreProcessor p : pipeline) cleaned = p.process(cleaned);

            System.out.println("Raw:     " + raw);
            System.out.println("Cleaned: " + cleaned);
            System.out.print("Tokens:  ");

            try (TokenStream stream = analyzer.tokenStream("field", new StringReader(cleaned))) {
                CharTermAttribute termAttr = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                while (stream.incrementToken()) System.out.print(termAttr + " ");
                stream.end();
            }

            System.out.println("\n================================");
            if (++count >= 5) break;
        }

        analyzer.close();
    }
}
