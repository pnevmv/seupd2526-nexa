package it.unipd.dei.se.nexa.config.tokenizer;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import org.apache.lucene.analysis.Tokenizer;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = LetterTokenizerConfig.class, name = "letter"),
        @JsonSubTypes.Type(value = StandardTokenizerConfig.class, name = "standard"),
        @JsonSubTypes.Type(value = WhitespaceTokenizerConfig.class, name = "whitespace")
})
public interface TokenizerConfig {
    Tokenizer toRuntime();
}
