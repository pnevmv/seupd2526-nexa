package it.unipd.dei.se.nexa.config.stemmer.de;

import it.unipd.dei.se.nexa.config.stemmer.StemFilterConfig;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.tartarus.snowball.ext.GermanStemmer;

public record GermanSnowballStemFilterConfig() implements StemFilterConfig {
    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {
        return new SnowballFilter(tokenStream, new GermanStemmer());
    }
}