package it.unipd.dei.se.nexa.config.tokenizer;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;

public record StandardTokenizerConfig() implements ITokenizerConfig {
    @Override
    public Tokenizer toRuntime() {
        return new StandardTokenizer();
    }
}
