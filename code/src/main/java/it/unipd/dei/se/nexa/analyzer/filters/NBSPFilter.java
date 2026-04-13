package it.unipd.dei.se.nexa.analyzer.filters;

import java.io.IOException;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

public class NBSPFilter extends TokenFilter {

    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);

    public NBSPFilter(TokenStream input) {
        super(input);
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (input.incrementToken()) {
            String term = termAttr.toString().replace("\u00A0", "").trim();
            termAttr.setEmpty().append(term);
            return true;
        }
        return false;
    }
}