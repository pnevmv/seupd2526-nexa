package it.unipd.dei.se.nexa.config.filters.agnostic;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.LengthFilter;

public record LengthFilterConfig(int minLength, int maxLength) implements TokenFilterConfig {

    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {
        return new LengthFilter(tokenStream, minLength, maxLength);
    }
}
