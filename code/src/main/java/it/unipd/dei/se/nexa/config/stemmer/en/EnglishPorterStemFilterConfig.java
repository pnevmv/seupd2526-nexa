package it.unipd.dei.se.nexa.config.stemmer.en;

import it.unipd.dei.se.nexa.config.stemmer.StemFilterConfig;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.PorterStemFilter;


public record EnglishPorterStemFilterConfig() implements StemFilterConfig {
    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {
        return new PorterStemFilter(tokenStream);
    }
}