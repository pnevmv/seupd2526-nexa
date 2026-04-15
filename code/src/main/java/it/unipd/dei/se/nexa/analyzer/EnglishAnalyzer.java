package it.unipd.dei.se.nexa.analyzer;

import java.io.IOException;
import java.io.StringReader;

import it.unipd.dei.se.nexa.analyzer.filters.*;
import it.unipd.dei.se.nexa.utility.ConfigManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LetterTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;

import org.apache.lucene.analysis.en.EnglishPossessiveFilter;
import org.apache.lucene.analysis.en.PorterStemFilter;

import org.apache.lucene.analysis.icu.ICUFoldingFilter;
import org.apache.lucene.analysis.miscellaneous.LengthFilter;
import org.apache.lucene.analysis.miscellaneous.RemoveDuplicatesTokenFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPLemmatizerFilter;
import org.apache.lucene.analysis.opennlp.OpenNLPTokenizer;
import org.apache.lucene.analysis.shingle.ShingleFilter;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import org.tartarus.snowball.ext.EnglishStemmer;

import static it.unipd.dei.se.nexa.analyzer.AnalyzerUtil.*;

/**
 * The EnglishAnalyzer class extends Lucene's Analyzer class and provides
 * functionality for analyzing English text documents. It includes options
 * for specifying different tokenizers, stemming filters, and other settings.
 */
public class EnglishAnalyzer extends Analyzer {

    /**
     * Represents which type of tokenizer to use.
     * Whitespace -> a tokenizer that divides text at whitespace characters
     * Letter -> a tokenizer that divides text at non-letters
     * Standard -> a tokenizer that divides text according to advanced rules
     * NLP -> openNLP tokenizer
     */
    public enum TokenizerType {
        WHITESPACE,
        LETTER,
        STANDARD,
        NLP
    }

    /**
     * Represents which type of stemmer to use.
     * Snowball -> snowball stemmer
     * Porter -> A TokenFilter that applies PorterStemmer to stem English words.
     * Nlp -> openNLP lemmatizer
     * None -> no stemmer should be applied
     */
    public enum StemFilterType {
        PORTER,
        SNOWBALL,
        NLP,
        NONE,
    }

    private final TokenizerType tokenizerType;
    private final StemFilterType stemFilterType;
    private final Integer minLength;
    private final Integer maxLength;
    private static final int MAX_WORD = 40; // since English medical/scientific words can be long
    private final String stopListFilePath;

    /**
     * Configuration class initialized for English context
     */
    private static final ConfigManager config = ConfigManager.getInstance("en");

    /**
     * The constructor for our Analyzer. It takes several parameters as input
     * that allow to specify its behaviour.
     *
     * @param tokenizerType    the type of tokenizer that should be used
     * @param minLength        the minimum allowed length of each token
     * @param maxLength        the maximum allowed length of each token
     * @param stopListFilePath the name of the stop list file
     * @param stemFilterType   the type of Stem filter to apply
     */
    public EnglishAnalyzer(TokenizerType tokenizerType, int minLength, int maxLength,
                           String stopListFilePath, StemFilterType stemFilterType) {
        super();
        if (stopListFilePath.isEmpty())
            throw new IllegalArgumentException("Stop list file name cannot be empty.");

        this.tokenizerType = tokenizerType;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.stopListFilePath = stopListFilePath;
        this.stemFilterType = stemFilterType;
    }

    /**
     * EnglishAnalyzer initializes its parameters based on a YAML configuration file.
     * It requires a "config_en.yml" with language set to "English".
     */
    public EnglishAnalyzer() {
        super();

        String language = config.getString("language");
        if (!"english".equalsIgnoreCase(language)) {
            throw new IllegalArgumentException("Unsupported document language: ".concat(language));
        }

        this.stopListFilePath = config.getString("customStopList");
        this.minLength = Math.clamp(config.getInt("minLength"), 0, MAX_WORD);
        this.maxLength = Math.clamp(config.getInt("maxLength"), 0, MAX_WORD);

        if (this.maxLength < this.minLength) {
            throw new IllegalArgumentException("maxLength (" + this.maxLength + ") cannot be less than minLength (" + this.minLength + ")");
        }

        // Setting the tokenizer
        String tokenizerName = config.getString("tokenizerType");
        switch (tokenizerName) {
            case "Whitespace" -> this.tokenizerType = TokenizerType.WHITESPACE;
            case "Standard" -> this.tokenizerType = TokenizerType.STANDARD;
            case "Letter" -> this.tokenizerType = TokenizerType.LETTER;
            case "Nlp" -> this.tokenizerType = TokenizerType.NLP;
            default -> throw new IllegalArgumentException("Bad initialization of Analyzer through YAML file");
        }

        // Setting the stemming
        String stemFilter = config.getString("stemFilter");
        switch (stemFilter) {
            case "Porter" -> this.stemFilterType = StemFilterType.PORTER;
            case "SnowBall" -> this.stemFilterType = StemFilterType.SNOWBALL;
            case "Nlp" -> this.stemFilterType = StemFilterType.NLP;
            default -> this.stemFilterType = StemFilterType.NONE;
        }
    }

    /**
     * Creates a Token Stream for English processing.
     * Includes English-specific filters like EnglishPossessiveFilter.
     *
     * @param fieldName the name of the field
     * @return the Token Stream
     */
    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer source;
        TokenStream filter;

        source = switch (tokenizerType) {
            case WHITESPACE -> new WhitespaceTokenizer();
            case LETTER -> new LetterTokenizer();
            case STANDARD -> new StandardTokenizer();
            case NLP -> {
                try {
                    yield new OpenNLPTokenizer(TokenStream.DEFAULT_TOKEN_ATTRIBUTE_FACTORY,
                            loadSentenceDetectorModel(), loadTokenizerModel());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };

        filter = new LowerCaseFilter(source);

        // English specific: strips possessives (e.g., "Alzheimer's" -> "Alzheimer")
        filter = new EnglishPossessiveFilter(filter);

        if (Boolean.TRUE.equals(config.getBool("repeatedLetterFilter")))
            filter = new repetedLetterFilter(filter);

        if (Boolean.TRUE.equals(config.getBool("expansionFilter")))
            filter = new AbbreviationExpansionFilter(filter, AnalyzerUtil.getAbbreviationMap("en"));

        filter = new ICUFoldingFilter(filter);
        filter = new NBSPFilter(filter);
        filter = new RemoveDuplicatesTokenFilter(filter);

        if (Boolean.TRUE.equals(config.getBool("posOpnNLPFilter")))
            filter = new CompoundPOSTokenFilter(filter, loadPosTaggerModel());

        if (Boolean.TRUE.equals(config.getBool("nGramsFilter")))
            filter = new ShingleFilter(filter, config.getInt("shingleSize"));

        if (minLength != null && maxLength != null)
            filter = new LengthFilter(filter, minLength, maxLength);

        if (!StringUtils.isBlank(stopListFilePath))
            filter = new StopFilter(filter, AnalyzerUtil.loadStopList(this.stopListFilePath));

        if (Boolean.TRUE.equals(config.getBool("positionFilter")))
            filter = new PositionFilter(filter, config.getInt("positionIncrement"));

        // Applying the chosen English stemming strategy
        switch (stemFilterType) {
            case PORTER -> filter = new PorterStemFilter(filter);
            case SNOWBALL -> filter = new SnowballFilter(filter, new EnglishStemmer());
            case NLP -> filter = new OpenNLPLemmatizerFilter(filter, loadLemmatizerModel());
            case NONE -> {}
        }

        return new TokenStreamComponents(source, filter);
    }
}



