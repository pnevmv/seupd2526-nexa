package it.unipd.dei.se.nexa.analyzer;

import it.unipd.dei.se.nexa.analyzer.filters.AbbreviationExpansionFilter;
import it.unipd.dei.se.nexa.analyzer.filters.CompoundPOSTokenFilter;
import it.unipd.dei.se.nexa.analyzer.filters.NBSPFilter;
import it.unipd.dei.se.nexa.analyzer.filters.PositionFilter;
import it.unipd.dei.se.nexa.analyzer.filters.RepeatedLetterFilter;
import it.unipd.dei.se.nexa.utility.ConfigManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.core.LetterTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.de.GermanLightStemFilter;
import org.apache.lucene.analysis.de.GermanMinimalStemFilter;
import org.apache.lucene.analysis.de.GermanNormalizationFilter;
import org.apache.lucene.analysis.de.GermanStemFilter;
import org.apache.lucene.analysis.icu.ICUFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.LengthFilter;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPLemmatizerFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPTokenizer;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.synonym.SynonymGraphFilter;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.tartarus.snowball.ext.GermanStemmer;

import java.io.IOException;
import java.io.StringReader;

import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.getAbbreviationMap;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadLemmatizerModel;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadPosTaggerModel;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadSentenceDetectorModel;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadStopList;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadSynonymMap;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadTokenizerModel;

/**
 * Analyzer implementation for German documents.
 */
public class GermanAnalyzer extends Analyzer {

    public enum TokenizerType {
        WHITESPACE,
        LETTER,
        STANDARD,
        NLP
    }

    public enum StemFilterType {
        GERMANMINIMAL,
        GERMANLIGHT,
        GERMAN,
        SNOWBALL,
        NLP,
        NONE
    }

    private static final int MAX_WORD = 36;
    private static final ConfigManager config = ConfigManager.getInstance("de");

    private final TokenizerType tokenizerType;
    private final StemFilterType stemFilterType;
    private final Integer minLength;
    private final Integer maxLength;
    private final String stopListFilePath;
    private final SynonymMap synonymMap;

    public GermanAnalyzer(final TokenizerType tokenizerType, final int minLength, final int maxLength,
                          final String stopListFilePath, final StemFilterType stemFilterType) {
        super();
        if (tokenizerType == null)
            throw new NullPointerException("Tokenizer type cannot be null.");
        if (stemFilterType == null)
            throw new NullPointerException("Stem filter type cannot be null.");
        if (stopListFilePath == null)
            throw new NullPointerException("Stop list file path cannot be null.");
        if (minLength < 0)
            throw new IllegalArgumentException("minLength cannot be negative.");
        if (maxLength < 0)
            throw new IllegalArgumentException("maxLength cannot be negative.");
        if (maxLength < minLength)
            throw new IllegalArgumentException("maxLength (" + maxLength
                    + ") cannot be smaller than minLength (" + minLength + ")");

        this.tokenizerType = tokenizerType;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.stopListFilePath = stopListFilePath;
        this.stemFilterType = stemFilterType;
        this.synonymMap = loadSynonymMap(config, GermanAnalyzer::createSynonymNormalizationAnalyzer);
    }

    public GermanAnalyzer() {
        super();

        String language = config.getString("language");
        if (!"german".equalsIgnoreCase(language)) {
            throw new IllegalArgumentException("Unsupported document language: ".concat(language));
        }

        this.stopListFilePath = defaultString(config.getString("customStopList"));
        this.minLength = Math.clamp(config.getInt("minLength"), 0, MAX_WORD);
        this.maxLength = Math.clamp(config.getInt("maxLength"), 0, MAX_WORD);

        if (this.maxLength < this.minLength) {
            throw new IllegalArgumentException("maxLength (" + this.maxLength
                    + ") cannot be smaller than minLength (" + this.minLength + ")");
        }

        this.tokenizerType = switch (config.getString("tokenizerType")) {
            case "Whitespace" -> TokenizerType.WHITESPACE;
            case "Standard" -> TokenizerType.STANDARD;
            case "Letter" -> TokenizerType.LETTER;
            case "Nlp" -> TokenizerType.NLP;
            default -> throw new IllegalArgumentException("Bad initialization of Analyzer through YAML file");
        };

        this.stemFilterType = switch (config.getString("stemFilter")) {
            case "GermanMinimal" -> StemFilterType.GERMANMINIMAL;
            case "GermanLight" -> StemFilterType.GERMANLIGHT;
            case "German" -> StemFilterType.GERMAN;
            case "SnowBall" -> StemFilterType.SNOWBALL;
            case "Nlp" -> StemFilterType.NLP;
            default -> StemFilterType.NONE;
        };

        this.synonymMap = loadSynonymMap(config, GermanAnalyzer::createSynonymNormalizationAnalyzer);
    }

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        Tokenizer source = switch (tokenizerType) {
            case WHITESPACE -> new WhitespaceTokenizer();
            case LETTER -> new LetterTokenizer();
            case STANDARD -> new StandardTokenizer();
            case NLP -> {
                try {
                    yield new OpenNLPTokenizer(TokenStream.DEFAULT_TOKEN_ATTRIBUTE_FACTORY,
                            loadSentenceDetectorModel(config), loadTokenizerModel(config));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        TokenStream filter = new LowerCaseFilter(source);

        if (Boolean.TRUE.equals(config.getBool("repeatedLetterFilter")))
            filter = new RepeatedLetterFilter(filter);

        if (Boolean.TRUE.equals(config.getBool("expansionFilter")))
            filter = new AbbreviationExpansionFilter(filter, getAbbreviationMap("de"));

        filter = new NBSPFilter(filter);
        filter = new GermanNormalizationFilter(filter);
        filter = new ICUFoldingFilter(filter);

        if (synonymMap != null) {
            filter = new SynonymGraphFilter(filter, synonymMap, true);
            filter = new FlattenGraphFilter(filter);
        }

        filter = new RemoveDuplicatesTokenFilter(filter);

        if (Boolean.TRUE.equals(config.getBool("posOpnNLPFilter")))
            filter = new CompoundPOSTokenFilter(filter, loadPosTaggerModel(config));

        if (minLength != null && maxLength != null)
            filter = new LengthFilter(filter, minLength, maxLength);

        if (!StringUtils.isBlank(stopListFilePath))
            filter = new StopFilter(filter, loadStopList(stopListFilePath));

        if (Boolean.TRUE.equals(config.getBool("nGramsFilter")))
            filter = new ShingleFilter(filter, config.getInt("shingleSize"));

        if (Boolean.TRUE.equals(config.getBool("positionFilter")))
            filter = new PositionFilter(filter, config.getInt("positionIncrement"));

        switch (stemFilterType) {
            case GERMANMINIMAL:
                filter = new GermanMinimalStemFilter(filter);
                break;
            case GERMANLIGHT:
                filter = new GermanLightStemFilter(filter);
                break;
            case GERMAN:
                filter = new GermanStemFilter(filter);
                break;
            case SNOWBALL:
                filter = new SnowballFilter(filter, new GermanStemmer());
                break;
            case NLP:
                filter = new OpenNLPLemmatizerFilter(filter, loadLemmatizerModel(config));
                break;
            case NONE:
                break;
        }

        return new TokenStreamComponents(source, filter);
    }

    private static Analyzer createSynonymNormalizationAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(final String fieldName) {
                final Tokenizer source = new StandardTokenizer();
                TokenStream filter = source;

                filter = new LowerCaseFilter(filter);
                filter = new NBSPFilter(filter);
                filter = new GermanNormalizationFilter(filter);
                filter = new ICUFoldingFilter(filter);

                return new TokenStreamComponents(source, filter);
            }
        };
    }

    public static void main(String[] args) throws IOException {
        final String text = "Fußgänger überqueren größere Straßen und Häuser.";

        GermanAnalyzer analyzer = new GermanAnalyzer();

        try (TokenStream stream = analyzer.tokenStream("field", new StringReader(text))) {
            stream.reset();

            final CharTermAttribute tokenTerm = stream.addAttribute(CharTermAttribute.class);
            final PositionIncrementAttribute posAttr = stream.addAttribute(PositionIncrementAttribute.class);

            int position = 0;
            while (stream.incrementToken()) {
                position += posAttr.getPositionIncrement();
                System.out.printf("+ token: %-20s | Position: %d%n", tokenTerm, position);
            }
        }
    }

    private static String defaultString(final String value) {
        return value == null ? "" : value;
    }
}
