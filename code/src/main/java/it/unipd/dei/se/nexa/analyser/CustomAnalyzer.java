package it.unipd.dei.se.nexa.analyser;

import java.io.IOException;
import java.io.StringReader;
import java.text.ParseException;
import java.util.List;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.StemFilterConfig;
import it.unipd.dei.se.nexa.config.config.tokenizer.TokenizerConfig;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.TokenStream;

import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import it.unipd.dei.se.nexa.parser.Publication;
import it.unipd.dei.se.nexa.parser.PublicationParser;

import org.apache.lucene.analysis.LowerCaseFilter;

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