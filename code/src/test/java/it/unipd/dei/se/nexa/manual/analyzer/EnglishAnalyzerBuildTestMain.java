package it.unipd.dei.se.nexa.manual.analyzer;

import it.unipd.dei.se.nexa.config.analyzer.EnglishAnalyzerConfig;
import it.unipd.dei.se.nexa.config.filters.agnostic.LowerCaseFilterConfig;
import it.unipd.dei.se.nexa.config.filters.en.EnglishDefaultStopFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.en.EnglishMinimalStemFilterConfig;
import it.unipd.dei.se.nexa.config.tokenizer.StandardTokenizerConfig;
import org.apache.lucene.analysis.Analyzer;

import java.util.List;

public class EnglishAnalyzerBuildTestMain {
    public static void main(String[] args) {
        EnglishAnalyzerConfig config = new EnglishAnalyzerConfig(
                List.of(
                        new LowerCaseFilterConfig(),
                        new EnglishDefaultStopFilterConfig()
                ),
                new StandardTokenizerConfig(),
                new EnglishMinimalStemFilterConfig()
        );

        Analyzer analyzer = config.toRuntime();
        System.out.println("Analyzer created: " + analyzer.getClass().getName());
        analyzer.close();
    }
}