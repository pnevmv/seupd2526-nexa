package it.unipd.dei.se.nexa.config.tokenizer;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LetterTokenizer;

public record LetterTokenizerConfig() implements TokenizerConfig {
    @Override
    public Tokenizer toRuntime() {
        return new LetterTokenizer();
    }
}
