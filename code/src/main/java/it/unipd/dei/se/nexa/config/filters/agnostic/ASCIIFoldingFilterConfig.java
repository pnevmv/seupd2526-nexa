package it.unipd.dei.se.nexa.config.filters.agnostic;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;

public record ASCIIFoldingFilterConfig() implements TokenFilterConfig {
    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {

        return new ASCIIFoldingFilter(tokenStream);
    }
}
