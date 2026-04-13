package it.unipd.dei.se.nexa.analyzer.filters;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.IOException;
import java.util.regex.Pattern;


public final class repetedLetterFilter extends TokenFilter {
    private static final Pattern REPEATED_CHARS = Pattern.compile("(.)\\1{2,}");
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);

    public repetedLetterFilter(TokenStream input) {
        super(input);
    }

    @Override
    public final boolean incrementToken() throws IOException {
        if (input.incrementToken()) {
            String term = termAtt.toString();
            String normalized = REPEATED_CHARS.matcher(term).replaceAll("$1$1");
            if (!term.equals(normalized)) {
                termAtt.setEmpty().append(normalized);
            }
            return true;
        }
        return false;
    }
}