package it.unipd.dei.se.nexa.analyzer.filters;

import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;

import java.io.IOException;


public final class PositionFilter extends TokenFilter {

    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);

    private boolean isFirstToken = true;

    // Constructor that allows setting a custom position increment value
    public PositionFilter(TokenStream input, int positionIncrement) {
        super(input);
        if (positionIncrement < 0)
            throw new IllegalArgumentException("positionIncrement may not be negative");
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (input.incrementToken()) {
            if (isFirstToken) {
                posIncrAtt.setPositionIncrement(1);
                isFirstToken = false;
            } else {
                posIncrAtt.setPositionIncrement(1);
            }
            return true;
        }
        return false;
    }
}


