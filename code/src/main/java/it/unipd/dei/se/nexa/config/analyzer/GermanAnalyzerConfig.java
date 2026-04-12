package it.unipd.dei.se.nexa.config.analyzer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import it.unipd.dei.se.nexa.analyzer.CustomAnalyzer;
import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.StemFilterConfig;
import it.unipd.dei.se.nexa.config.tokenizer.TokenizerConfig;
import org.apache.lucene.analysis.Analyzer;

import java.util.ArrayList;
import java.util.List;

public record GermanAnalyzerConfig(
        @JsonDeserialize(as = ArrayList.class)
        List<TokenFilterConfig> tokenFilters,
        TokenizerConfig tokenizer,
        StemFilterConfig stemFilter
) implements AnalyzerConfig {

    @Override
    public Analyzer toRuntime() {
        return new CustomAnalyzer(tokenFilters, tokenizer, stemFilter);
    }
}