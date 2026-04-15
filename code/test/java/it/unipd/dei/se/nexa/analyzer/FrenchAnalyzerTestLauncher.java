package it.unipd.dei.se.nexa.analyzer;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.io.StringReader;

public class FrenchAnalyzerTestLauncher {

    public static void main(String[] args) throws IOException {
        final String text = "101boyvideos.com - Et 50 autres sites similaires Ã ";

        FrenchAnalyzer analyzer = new FrenchAnalyzer();

        try (TokenStream stream = analyzer.tokenStream("field", new StringReader(text))) {
            stream.reset();

            final CharTermAttribute tokenTerm = stream.addAttribute(CharTermAttribute.class);
            final PositionIncrementAttribute posAttr = stream.addAttribute(PositionIncrementAttribute.class);

            int position = 0;
            while (stream.incrementToken()) {
                position += posAttr.getPositionIncrement();
                System.out.printf("+ token: %-20s | Position: %d%n", tokenTerm, position);
            }

            stream.end();
        }
    }
}
