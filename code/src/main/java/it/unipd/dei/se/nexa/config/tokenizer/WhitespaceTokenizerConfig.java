package it.unipd.dei.se.nexa.analyser;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;

public record WhitespaceTokenizerConfig() implements ITokenizerConfig {
    @Override
    public Tokenizer toRuntime() {
        return new WhitespaceTokenizer();
    }
}
