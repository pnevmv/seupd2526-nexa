package it.unipd.dei.se.nexa.analyzer;

import java.io.IOException;
import java.text.ParseException;
import java.util.List;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.StemFilterConfig;
import it.unipd.dei.se.nexa.config.config.tokenizer.TokenizerConfig;

import lombok.RequiredArgsConstructor;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.TokenStream;


@RequiredArgsConstructor
public class CustomAnalyzer extends Analyzer {
    private final List<TokenFilterConfig> tokenFilters;
    private final TokenizerConfig tokenizerConfig;
    private final StemFilterConfig stemFilterConfig;


    @Override
    protected TokenStreamComponents createComponents(String fieldName) {

        Tokenizer tokenizer = tokenizerConfig.toRuntime();

        try {
            TokenStream tokens = tokenizer;

            for (TokenFilterConfig tokenFilter : tokenFilters) {
                tokens = tokenFilter.toRuntime(tokens);
            }

            tokens = stemFilterConfig.toRuntime(tokens);

            return new TokenStreamComponents(tokenizer, tokens);

        } catch (IOException | ParseException e) {
            throw new IllegalStateException("Unable to add filters to TokenStream", e);
        }
    }
}