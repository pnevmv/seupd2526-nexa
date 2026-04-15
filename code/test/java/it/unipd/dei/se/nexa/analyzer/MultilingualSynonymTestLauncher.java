package it.unipd.dei.se.nexa.analyzer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import java.io.IOException;
import java.io.StringReader;

/**
 * Small launcher to inspect synonym expansion in the configured analyzers.
 * <p>
 * These samples use the default YAML-backed analyzers, so later filters such
 * as POS tagging or shingles can further transform the synonym-expanded terms.
 */
public final class MultilingualSynonymTestLauncher {

    private record Sample(String label, String text) {
    }

    private static final Sample FRENCH_SAMPLE = new Sample("French", "cabale");
    private static final Sample ENGLISH_SAMPLE = new Sample("English", "individual");
    private static final Sample GERMAN_SAMPLE = new Sample("German", "Training");

    private MultilingualSynonymTestLauncher() {
    }

    public static void main(final String[] args) throws IOException {
        System.out.println("=== MULTILINGUAL SYNONYM TEST ===");
        System.out.println("Each sample starts from a non-canonical synonym form.");
        System.out.println("Look for the analyzer to inject or normalize it toward the canonical term.");
        System.out.println();

        try (FrenchAnalyzer frenchAnalyzer = new FrenchAnalyzer();
             EnglishAnalyzer englishAnalyzer = new EnglishAnalyzer();
             GermanAnalyzer germanAnalyzer = new GermanAnalyzer()) {

            printAnalysis(FRENCH_SAMPLE, frenchAnalyzer);
            printAnalysis(ENGLISH_SAMPLE, englishAnalyzer);
            printAnalysis(GERMAN_SAMPLE, germanAnalyzer);
        }
    }

    private static void printAnalysis(final Sample sample,
                                      final Analyzer analyzer) throws IOException {
        System.out.println("--- " + sample.label() + " ---");
        System.out.println("Input: " + sample.text());

        try (TokenStream stream = analyzer.tokenStream("field", new StringReader(sample.text()))) {
            stream.reset();

            final CharTermAttribute termAttribute = stream.addAttribute(CharTermAttribute.class);
            final PositionIncrementAttribute positionAttribute = stream.addAttribute(PositionIncrementAttribute.class);
            final TypeAttribute typeAttribute = stream.addAttribute(TypeAttribute.class);

            int position = 0;
            while (stream.incrementToken()) {
                position += positionAttribute.getPositionIncrement();
                System.out.printf("+ token: %-20s | position: %d | type: %s%n",
                        termAttribute, position, typeAttribute.type());
            }

            stream.end();
        }

        System.out.println();
    }
}
