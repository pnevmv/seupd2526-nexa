package it.unipd.dei.se.nexa.manual.analyzer;

import it.unipd.dei.se.nexa.config.analyzer.EnglishAnalyzerConfig;
import it.unipd.dei.se.nexa.config.filters.agnostic.LowerCaseFilterConfig;
import it.unipd.dei.se.nexa.config.filters.en.EnglishDefaultStopFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.en.EnglishMinimalStemFilterConfig;
import it.unipd.dei.se.nexa.config.tokenizer.StandardTokenizerConfig;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.StringReader;
import java.util.List;

public class EnglishAnalyzerTokenTestMain {
    public static void main(String[] args) throws Exception {
        EnglishAnalyzerConfig config = new EnglishAnalyzerConfig(
                List.of(
                        new LowerCaseFilterConfig(),
                        new EnglishDefaultStopFilterConfig()
                ),
                new StandardTokenizerConfig(),
                new EnglishMinimalStemFilterConfig()
        );

        Analyzer analyzer = config.toRuntime();

        String text = "The researchers' findings about vaccines were published in major journals.";

        try (TokenStream stream = analyzer.tokenStream("field", new StringReader(text))) {
            CharTermAttribute termAttr = stream.addAttribute(CharTermAttribute.class);
            stream.reset();

            while (stream.incrementToken()) {
                System.out.println(termAttr.toString());
            }

            stream.end();
        }

        analyzer.close();
    }
}