package it.unipd.dei.se.nexa.config.filters.de;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.de.GermanAnalyzer;

public record GermanDefaultStopFilterConfig() implements TokenFilterConfig {
    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {
        return new StopFilter(tokenStream, GermanAnalyzer.getDefaultStopSet());
    }
}