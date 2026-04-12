package it.unipd.dei.se.nexa.config.filters.agnostic;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.core.StopFilter;


public record GenericStopFilterConfig(String filePath) implements TokenFilterConfig {
    public TokenFilter toRuntime(TokenStream tokenStream) throws IOException {

        Path file = Paths.get(filePath);

        CharArraySet words = new CharArraySet(100, true);

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            words.addAll(WordlistLoader.getWordSet(reader, StandardCharsets.UTF_8.name(), words));
        }

        return new StopFilter(tokenStream, words);
    }
}