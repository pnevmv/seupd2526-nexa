package it.unipd.dei.se.nexa.config.analyzer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.StemFilterConfig;
import it.unipd.dei.se.nexa.config.tokenizer.TokenizerConfig;

import org.apache.lucene.analysis.Analyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the English language analyzer using customizable modular components.
 *
 * <p>This configuration builds an English-specific pipeline including a tokenizer,
 * an English stemmer, and a list of token filters.
 * It is deserialized from JSON and converted into a {@link CustomAnalyzer} instance
 * via {@link #toRuntime()}.</p>
 *
 * <p>Used when the analyzer configuration type is {@code "english"} in the JSON file.</p>
 *
 * <pre>{@code
 * {
 * "type": "english",
 * "tokenizer": { "type": "standard" },
 * "tokenFilters": [ { "type": "lowercase" }, { "type": "stop" } ],
 * "stemFilter": { "type": "english_minimal" }
 * }
 * }</pre>
 *
 * @param tokenFilters list of token filter configurations for English text
 * @param tokenizer the tokenizer configuration (e.g., standard tokenizer)
 * @param stemFilter English-specific stemming filter (e.g., english_minimal or porter)
 */
public record EnglishAnalyzerConfig(
        @JsonDeserialize(as = ArrayList.class)
        List<TokenFilterConfig> tokenFilters,
        TokenizerConfig tokenizer,
        StemFilterConfig stemFilter
) implements AnalyzerConfig {
    public Analyzer toRuntime() {
        return new CustomAnalyzer(tokenFilters, tokenizer, stemFilter);
    }
}
