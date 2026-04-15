package it.unipd.dei.se.nexa.analyzer;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.io.StringReader;

public class EnglishAnalyzerTestLauncher {

    public static void main(String[] args) throws IOException {
        final String text = "John's researchers were running repeated studies across universities.";

        EnglishAnalyzer analyzer = new EnglishAnalyzer(
                EnglishAnalyzer.TokenizerType.STANDARD,
                2,
                36,
                "",
                EnglishAnalyzer.StemFilterType.PORTER
        );

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
