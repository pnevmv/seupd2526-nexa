package it.unipd.dei.se.nexa.config.filters.en;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishPossessiveFilter;

public record EnglishPossessiveFilterConfig() implements TokenFilterConfig {

    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {
        return new EnglishPossessiveFilter(tokenStream);
    }
}

