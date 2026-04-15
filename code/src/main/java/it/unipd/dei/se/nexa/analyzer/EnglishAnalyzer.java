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
import org.apache.lucene.analysis.en.EnglishMinimalStemFilter;
import org.apache.lucene.analysis.en.EnglishPossessiveFilter;
import org.apache.lucene.analysis.en.KStemFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;
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
import org.tartarus.snowball.ext.EnglishStemmer;

import java.io.IOException;

import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.getAbbreviationMap;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadLemmatizerModel;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadPosTaggerModel;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadSentenceDetectorModel;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadStopList;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadSynonymMap;
import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.loadTokenizerModel;

/**
 * Lucene analyzer for English text.
 * <p>
 * The analyzer is configured through {@code config_en.yml} and supports
 * multiple tokenizers, optional normalization filters, stop lists, and
 * stemming or lemmatization strategies.
 */
public class EnglishAnalyzer extends Analyzer {

    private static final String LANGUAGE_CODE = "en";
    private static final String LANGUAGE_NAME = "English";

    /**
     * Upper bound for token length accepted by the analyzer configuration.
     */
    private static final int MAX_WORD_LENGTH = 36;

    private static final ConfigManager CONFIG = ConfigManager.getInstance(LANGUAGE_CODE);

    /**
     * Supported tokenizer types.
     */
    public enum TokenizerType {
        WHITESPACE,
        LETTER,
        STANDARD,
        NLP
    }

    /**
     * Supported stemming / lemmatization strategies for English.
     */
    public enum StemFilterType {
        ENGLISHMINIMAL,
        KSTEM,
        PORTER,
        SNOWBALL,
        NLP,
        NONE
    }

    private final TokenizerType tokenizerType;
    private final StemFilterType stemFilterType;
    private final int minLength;
    private final int maxLength;
    private final String stopListResourcePath;
    private final SynonymMap synonymMap;

    /**
     * Creates an English analyzer with explicit parameters.
     *
     * @param tokenizerType tokenizer type to use.
     * @param minLength minimum accepted token length.
     * @param maxLength maximum accepted token length.
     * @param stopListResourcePath classpath resource path of the stop list, or empty string if unused.
     * @param stemFilterType stemming or lemmatization strategy.
     */
    public EnglishAnalyzer(final TokenizerType tokenizerType,
                           final int minLength,
                           final int maxLength,
                           final String stopListResourcePath,
                           final StemFilterType stemFilterType) {
        super();

        if (tokenizerType == null) {
            throw new NullPointerException("Tokenizer type cannot be null.");
        }
        if (stemFilterType == null) {
            throw new NullPointerException("Stem filter type cannot be null.");
        }
        if (stopListResourcePath == null) {
            throw new NullPointerException("Stop list resource path cannot be null.");
        }
        if (minLength < 0) {
            throw new IllegalArgumentException("minLength cannot be negative.");
        }
        if (maxLength < 0) {
            throw new IllegalArgumentException("maxLength cannot be negative.");
        }
        if (maxLength < minLength) {
            throw new IllegalArgumentException(
                    "maxLength (" + maxLength + ") cannot be smaller than minLength (" + minLength + ").");
        }

        this.tokenizerType = tokenizerType;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.stopListResourcePath = stopListResourcePath;
        this.stemFilterType = stemFilterType;
        this.synonymMap = loadSynonymMap(CONFIG, EnglishAnalyzer::createSynonymNormalizationAnalyzer);
    }

    /**
     * Creates an English analyzer using the YAML configuration.
     */
    public EnglishAnalyzer() {
        super();

        final String language = CONFIG.getString("language");
        if (!LANGUAGE_NAME.equalsIgnoreCase(language)) {
            throw new IllegalArgumentException("Unsupported analyzer language: " + language);
        }

        this.stopListResourcePath = defaultString(CONFIG.getString("customStopList"));
        this.minLength = clamp(CONFIG.getInt("minLength"), 0, MAX_WORD_LENGTH);
        this.maxLength = clamp(CONFIG.getInt("maxLength"), 0, MAX_WORD_LENGTH);

        if (this.maxLength < this.minLength) {
            throw new IllegalArgumentException(
                    "maxLength (" + this.maxLength + ") cannot be smaller than minLength (" + this.minLength + ").");
        }

        this.tokenizerType = parseTokenizerType(CONFIG.getString("tokenizerType"));
        this.stemFilterType = parseStemFilterType(CONFIG.getString("stemFilter"));
        this.synonymMap = loadSynonymMap(CONFIG, EnglishAnalyzer::createSynonymNormalizationAnalyzer);
    }

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        final Tokenizer source = createTokenizer(tokenizerType);
        TokenStream filter = source;

        filter = new EnglishPossessiveFilter(filter);
        filter = new LowerCaseFilter(filter);

        if (Boolean.TRUE.equals(CONFIG.getBool("repeatedLetterFilter"))) {
            filter = new RepeatedLetterFilter(filter);
        }

        if (Boolean.TRUE.equals(CONFIG.getBool("expansionFilter"))) {
            filter = new AbbreviationExpansionFilter(filter, getAbbreviationMap(LANGUAGE_CODE));
        }

        filter = new NBSPFilter(filter);
        filter = new ICUFoldingFilter(filter);

        if (synonymMap != null) {
            filter = new SynonymGraphFilter(filter, synonymMap, true);
            filter = new FlattenGraphFilter(filter);
        }

        filter = new RemoveDuplicatesTokenFilter(filter);

        if (Boolean.TRUE.equals(CONFIG.getBool("posOpnNLPFilter"))) {
            filter = new CompoundPOSTokenFilter(filter, loadPosTaggerModel(CONFIG));
        }

        if (Boolean.TRUE.equals(CONFIG.getBool("nGramsFilter"))) {
            filter = new ShingleFilter(filter, CONFIG.getInt("shingleSize"));
        }

        filter = new LengthFilter(filter, minLength, maxLength);

        if (!StringUtils.isBlank(stopListResourcePath)) {
            filter = new StopFilter(filter, loadStopList(stopListResourcePath));
        }

        if (Boolean.TRUE.equals(CONFIG.getBool("positionFilter"))) {
            filter = new PositionFilter(filter, CONFIG.getInt("positionIncrement"));
        }

        filter = applyStemFilter(filter, stemFilterType);

        return new TokenStreamComponents(source, filter);
    }

    private static Tokenizer createTokenizer(final TokenizerType tokenizerType) {
        return switch (tokenizerType) {
            case WHITESPACE -> new WhitespaceTokenizer();
            case LETTER -> new LetterTokenizer();
            case STANDARD -> new StandardTokenizer();
            case NLP -> {
                try {
                    yield new OpenNLPTokenizer(
                            TokenStream.DEFAULT_TOKEN_ATTRIBUTE_FACTORY,
                            loadSentenceDetectorModel(CONFIG),
                            loadTokenizerModel(CONFIG)
                    );
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to initialize OpenNLP tokenizer.", e);
                }
            }
        };
    }

    private static TokenStream applyStemFilter(final TokenStream filter, final StemFilterType stemFilterType) {
        return switch (stemFilterType) {
            case ENGLISHMINIMAL -> new EnglishMinimalStemFilter(filter);
            case KSTEM -> new KStemFilter(filter);
            case PORTER -> new PorterStemFilter(filter);
            case SNOWBALL -> new SnowballFilter(filter, new EnglishStemmer());
            case NLP -> new OpenNLPLemmatizerFilter(filter, loadLemmatizerModel(CONFIG));
            case NONE -> filter;
        };
    }

    private static Analyzer createSynonymNormalizationAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(final String fieldName) {
                final Tokenizer source = new StandardTokenizer();
                TokenStream filter = source;

                filter = new EnglishPossessiveFilter(filter);
                filter = new LowerCaseFilter(filter);
                filter = new NBSPFilter(filter);
                filter = new ICUFoldingFilter(filter);

                return new TokenStreamComponents(source, filter);
            }
        };
    }

    private static TokenizerType parseTokenizerType(final String tokenizerName) {
        if (tokenizerName == null) {
            throw new NullPointerException("tokenizerType cannot be null in YAML configuration.");
        }

        return switch (tokenizerName) {
            case "Whitespace" -> TokenizerType.WHITESPACE;
            case "Letter" -> TokenizerType.LETTER;
            case "Standard" -> TokenizerType.STANDARD;
            case "Nlp" -> TokenizerType.NLP;
            default -> throw new IllegalArgumentException(
                    "Unsupported tokenizerType in YAML configuration: " + tokenizerName);
        };
    }

    private static StemFilterType parseStemFilterType(final String stemFilterName) {
        if (stemFilterName == null) {
            throw new NullPointerException("stemFilter cannot be null in YAML configuration.");
        }

        return switch (stemFilterName) {
            case "EnglishMinimal" -> StemFilterType.ENGLISHMINIMAL;
            case "KStem" -> StemFilterType.KSTEM;
            case "Porter" -> StemFilterType.PORTER;
            case "SnowBall" -> StemFilterType.SNOWBALL;
            case "Nlp" -> StemFilterType.NLP;
            case "None" -> StemFilterType.NONE;
            default -> throw new IllegalArgumentException(
                    "Unsupported stemFilter in YAML configuration: " + stemFilterName);
        };
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static String defaultString(final String value) {
        return value == null ? "" : value;
    }
}
