package it.unipd.dei.se.nexa.analyzer;

import java.io.IOException;
import java.io.StringReader;

import de.ir_lab.longeval25.analyzer.filters.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.LetterTokenizer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.core.WhitespaceTokenizer;
import org.apache.lucene.analysis.fr.FrenchLightStemFilter;
import org.apache.lucene.analysis.fr.FrenchMinimalStemFilter;
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
import org.apache.lucene.analysis.util.ElisionFilter;
import org.tartarus.snowball.ext.FrenchStemmer;

import static de.ir_lab.longeval25.analyzer.AnalyzerUtil.loadLemmatizerModel;
import static de.ir_lab.longeval25.analyzer.AnalyzerUtil.loadPosTaggerModel;
import static de.ir_lab.longeval25.analyzer.AnalyzerUtil.loadSentenceDetectorModel;
import static de.ir_lab.longeval25.analyzer.AnalyzerUtil.loadTokenizerModel;
import de.ir_lab.longeval25.utility.ConfigManager;


/**
 * The FrenchAnalyzer class extends Lucene's Analyzer class and provides
 * functionality for analyzing French text documents. It includes options
 * for specifying different tokenizers, stemming filters, and other settings.
 */
public class FrenchAnalyzer extends Analyzer {


    /**
     * Represents which type of tokenizer to use.
     * Whitespace -> a tokenizer that divides text at whitespace characters
     * Letter -> a tokenizer that divides text at non-letters
     * Standard -> a tokenizer that divides text according to some more advanced rules (e.g., whitespaces, punctuation,...)
     * NLP -> openNLP tokenizer
     */
    public enum TokenizerType {
        /**
         * Tokenizes text based on whitespace characters.
         */
        WHITESPACE,
        /**
         * Tokenizes text based on letter boundaries.
         */
        LETTER,
        /**
         * Tokenizes text using a standard tokenizer.
         */
        STANDARD,
        /**
         * Tokenizes text using a openNLP tokenizer model.
         */
        NLP
    }

    /**
     * Represents which type of stemmer to use.
     * Snowball -> snowball stemmer
     * FrenchMinimal -> A TokenFilter that applies FrenchMinimalStemmer to stem French words.
     * FrenchLight -> Light Stemmer for French.
     * Nlp -> openNLP lemmatizer
     * None -> no stemmer should be applied
     */
    public enum StemFilterType {

        /**
         * Applies a light stemming algorithm for French language.
         */
        FRENCHLIGHT,

        /**
         * Applies a minimal stemming algorithm for French language.
         */
        FRENCHMINIMAL,

        /**
         * Applies the Snowball stemming algorithm, which is a generic stemming algorithm
         * supporting multiple languages.
         */
        SNOWBALL,

        /**
         * Applies the lemmatizer from openNLP, which is a generic stemming algorithm
         * supporting multiple languages.
         */
        NLP,

        /**
         * Indicates no stemming filter should be applied.
         */
        NONE,
    }


    /**
     * The type of tokenizer to be used
     */
    private final TokenizerType tokenizerType;

    /**
     * The type of stemmer to be used
     */
    private final StemFilterType stemFilterType;

    /**
     * The minimum token length (tokens with fewer characters will be discarded)
     */
    private final Integer minLength;

    /**
     * The maximum token length (tokens with more characters will be discarded)
     */
    private final Integer maxLength;

    /**
     * size of largest French word
     */
    private static final int MAX_WORD = 36;

    /**
     * The stop-list file name
     */
    private final String stopListFilePath;

    /**
     * Configuration class
     */
    private static final ConfigManager config = ConfigManager.getInstance();

    /**
     * The constructor for our Analyzer. It takes several parameters as input
     * that allow to specify its behaviour
     *
     * @param tokenizerType    the type of tokenizer that should be used
     * @param minLength        the minimum allowed length of each token
     * @param maxLength        the maximum allowed length of each token
     * @param stopListFilePath the name of the stop list file
     * @param stemFilterType   the type of Stem filter to apply
     */
    public FrenchAnalyzer(TokenizerType tokenizerType, int minLength, int maxLength,
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
     * FrenchAnalyzer is an analyzer designed specifically for processing French documents.
     * It initializes its parameters based on a YAML configuration file specified by the provided path.
     * The YAML file should contain settings for language, tokenizer type, stemming filter, custom stop list file path,
     * minimum and maximum word lengths.
     *
     * @throws IllegalArgumentException If the provided YAML file is invalid or contains unsupported parameters.
     */
    public FrenchAnalyzer() {
        super();

        String language = config.getString("language");
        if (!"french".equalsIgnoreCase(language)) {
            throw new IllegalArgumentException("Unsupported document language: ".concat(language));
        }

        this.stopListFilePath = config.getString("customStopList");
        this.minLength = Math.clamp(config.getInt("minLength"), 0, MAX_WORD);
        this.maxLength = Math.clamp(config.getInt("maxLength"), 0, MAX_WORD);

        if (this.maxLength < this.minLength) {
            throw new IllegalArgumentException("maxLength (" + this.maxLength + ") non può essere minore di minLength (" + this.minLength + ")");
        }

        //setting the tokenizer
        String tokenizerName = config.getString("tokenizerType");
        switch (tokenizerName) {
            case "Whitespace":
                this.tokenizerType = TokenizerType.WHITESPACE;
                break;
            case "Standard":
                this.tokenizerType = TokenizerType.STANDARD;
                break;
            case "Letter":
                this.tokenizerType = TokenizerType.LETTER;
                break;
            case "Nlp":
                this.tokenizerType = TokenizerType.NLP;
                break;
            default:
                throw new IllegalArgumentException("Bad initialization of Analyzer through YAML file");
        }

        //setting the stemming
        String stemFilter = config.getString("stemFilter");
        switch (stemFilter) {
            case "FrenchMinimal":
                this.stemFilterType = StemFilterType.FRENCHMINIMAL;
                break;
            case "FrenchLight":
                this.stemFilterType = StemFilterType.FRENCHLIGHT;
                break;
            case "SnowBall":
                this.stemFilterType = StemFilterType.SNOWBALL;
                break;
            case "Nlp":
                this.stemFilterType = StemFilterType.NLP;
                break;
            default:
                this.stemFilterType = StemFilterType.NONE;
                break;
        }

    }

    /**
     * Creates a Token Stream. This method must be implemented when implementing
     * the Analyzer abstract class. In this method you must create all the components
     * such as tokenizers, stemmers and stop lists that will be applied to your tokens.
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

        if (Boolean.TRUE.equals(config.getBool("repeatedLetterFilter")))
            filter = new repetedLetterFilter(filter);

        if (Boolean.TRUE.equals(config.getBool("expansionFilter")))
            filter = new AbbreviationExpansionFilter(filter, AnalyzerUtil.getAbbreviationMap());

        filter = new ICUFoldingFilter(filter);
        filter = new NBSPFilter(filter);
        filter = new ElisionFilter(filter, org.apache.lucene.analysis.fr.FrenchAnalyzer.DEFAULT_ARTICLES);
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

        switch (stemFilterType) {
            case FRENCHMINIMAL:
                filter = new FrenchMinimalStemFilter(filter);
                break;
            case FRENCHLIGHT:
                filter = new FrenchLightStemFilter(filter);
                break;
            case SNOWBALL:
                filter = new SnowballFilter(filter, new FrenchStemmer());
                break;
            case NLP:
                filter = new OpenNLPLemmatizerFilter(filter, loadLemmatizerModel());
                break;
            case NONE:
                break;
        }
        return new TokenStreamComponents(source, filter);
    }

    /**
     * it.unipd.dei.se.Main method of the class. This is done mainly for testing purposes.
     *
     * @param args command line arguments.
     * @throws IOException if something goes wrong while processing the text.
     */
    public static void main(String[] args) throws IOException {

        final String text = "101boyvideos.com - Et 50 autres sites similaires Ã ";


        //------------ LUCENE TOKENIZER----------------
        FrenchAnalyzer analyzer = new FrenchAnalyzer();

        try (TokenStream stream = analyzer.tokenStream("field", new StringReader(text))) {
            // Reset the stream before starting
            stream.reset();

            final CharTermAttribute tokenTerm = stream.addAttribute(CharTermAttribute.class);
            final PositionIncrementAttribute posAttr = stream.addAttribute(PositionIncrementAttribute.class);


            int position = 0;
            // Print all tokens and their respective positions until the stream is exhausted
            while (stream.incrementToken()) {
                position += posAttr.getPositionIncrement();
                System.out.printf("+ token: %-20s | Position: %d%n", tokenTerm.toString(), position);
            }
        }

    }
}