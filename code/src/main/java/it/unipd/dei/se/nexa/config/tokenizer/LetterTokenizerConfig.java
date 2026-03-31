package it.unipd.dei.se.nexa.analyser;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LetterTokenizer;

public record LetterTokenizerConfig() implements ITokenizerConfig {
    @Override
    public Tokenizer toRuntime() {
        return new LetterTokenizer();
    }
}
