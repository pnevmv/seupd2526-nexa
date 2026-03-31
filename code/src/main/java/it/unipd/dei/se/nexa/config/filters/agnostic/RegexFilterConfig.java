package it.unipd.dei.se.nexa.config.filters.agnostic;

import it.unipd.dei.se.nexa.config.filters.TokenFilterConfig;

import java.util.regex.Pattern;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.pattern.PatternReplaceFilter;

public record RegexFilterConfig(String regex, String replacement) implements TokenFilterConfig {

    @Override
    public TokenFilter toRuntime(TokenStream tokenStream) {
        Pattern pattern = Pattern.compile(regex);
        return new PatternReplaceFilter(tokenStream, pattern, replacement, true);
    }
}
