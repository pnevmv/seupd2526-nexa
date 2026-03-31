package it.unipd.dei.se.nexa.config.filters.agnostic;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;

import java.io.IOException;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.miscellaneous.TrimFilter;


public class TrimFilterConfig implements TokenFilterConfig {
    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) throws IOException {
        return new TrimFilter(tokenStream);
    }
}
