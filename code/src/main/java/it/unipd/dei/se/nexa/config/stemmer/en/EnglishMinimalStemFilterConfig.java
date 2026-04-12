package it.unipd.dei.se.nexa.config.stemmer.en;

import it.unipd.dei.se.nexa.config.stemmer.StemFilterConfig;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishMinimalStemFilter;


public record EnglishMinimalStemFilterConfig() implements StemFilterConfig {
    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {
        return new EnglishMinimalStemFilter(tokenStream);
    }
}