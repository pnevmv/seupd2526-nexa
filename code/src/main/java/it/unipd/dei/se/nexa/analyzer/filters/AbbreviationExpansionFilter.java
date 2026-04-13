package it.unipd.dei.se.nexa.analyzer.filters;

import java.io.IOException;
import java.util.Map;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;


public class AbbreviationExpansionFilter extends TokenFilter {
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final Map<String, String> abbreviationMap;

    public AbbreviationExpansionFilter(TokenStream input, Map<String, String> abbreviationMap) {
        super(input);
        this.abbreviationMap = abbreviationMap;
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!input.incrementToken()) return false;

        String token = termAttr.toString();
        String lowerToken = token.toLowerCase();

        if (abbreviationMap.containsKey(lowerToken)) {
            String expanded = abbreviationMap.get(lowerToken);
            termAttr.setEmpty();
            termAttr.append(expanded);
        }

        return true;
    }
}