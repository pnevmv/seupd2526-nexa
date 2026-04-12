package it.unipd.dei.se.nexa.config.filters.agnostic;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.index.IndexReader;

public class SpellCheckerFilterConfig implements TokenFilterConfig {
    private final int numberOfSuggestions;
    private final int minTermLength;
    private final IndexReader indexReader;

    @JsonCreator
    public SpellCheckerFilterConfig(
            @JsonProperty("numberOfSuggestions") int numberOfSuggestions,
            @JsonProperty("minTermLength") int minTermLength,
            @JacksonInject IndexReader indexReader
    ) {
        this.numberOfSuggestions = numberOfSuggestions;
        this.minTermLength = minTermLength;
        this.indexReader = indexReader;
    }

    public int getNumberOfSuggestions() {
        return numberOfSuggestions;
    }

    public int getMinTermLength() {
        return minTermLength;
    }

    public IndexReader getIndexReader() {
        return indexReader;
    }

    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {
        throw new UnsupportedOperationException("SpellCheckerFilterConfig not implemented yet.");
    }
}