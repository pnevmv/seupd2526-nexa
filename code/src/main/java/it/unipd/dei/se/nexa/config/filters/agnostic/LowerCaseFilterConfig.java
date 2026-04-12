package it.unipd.dei.se.nexa.config.filters.agnostic;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;

import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;

public record LowerCaseFilterConfig() implements TokenFilterConfig {
    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {

        return new LowerCaseFilter(tokenStream);
    }
}
