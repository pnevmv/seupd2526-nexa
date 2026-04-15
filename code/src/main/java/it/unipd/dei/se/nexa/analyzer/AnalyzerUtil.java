package it.unipd.dei.se.nexa.analyzer;

import it.unipd.dei.se.nexa.utility.ConfigManager;
import opennlp.tools.langdetect.LanguageDetectorModel;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerModel;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.opennlp.tools.NLPLemmatizerOp;
import org.apache.lucene.analysis.opennlp.tools.NLPSentenceDetectorOp;
import org.apache.lucene.analysis.opennlp.tools.NLPTokenizerOp;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility methods for analyzer resource loading.
 */
public final class AnalyzerUtil {

    private AnalyzerUtil() {
    }

    /**
     * Loads a stop list from a classpath resource.
     *
     * @param stopListResourcePath classpath resource path of the stop list.
     * @return a CharArraySet containing the loaded stop words.
     */
    public static CharArraySet loadStopList(@NotNull final String stopListResourcePath) {
        if (stopListResourcePath == null) {
            throw new NullPointerException("Stop list resource path cannot be null.");
        }
        if (stopListResourcePath.isBlank()) {
            throw new IllegalArgumentException("Stop list resource path cannot be blank.");
        }

        try (InputStream in = openResource(stopListResourcePath);
             Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return WordlistLoader.getWordSet(reader);
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load stop list %s: %s", stopListResourcePath, e.getMessage()), e);
        }
    }

    /**
     * Loads the OpenNLP tokenizer model defined in the provided configuration.
     *
     * @param config analyzer configuration.
     * @return the tokenizer model wrapper.
     */
    public static NLPTokenizerOp loadTokenizerModel(@NotNull final ConfigManager config) {
        final String modelResourcePath = requireConfigValue(config, "tokenizerModel");

        try (InputStream in = openResource(modelResourcePath)) {
            return new NLPTokenizerOp(new TokenizerModel(in));
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load tokenizer model %s: %s", modelResourcePath, e.getMessage()), e);
        }
    }

    /**
     * Loads the OpenNLP sentence detector model defined in the provided configuration.
     *
     * @param config analyzer configuration.
     * @return the sentence detector model wrapper.
     */
    public static NLPSentenceDetectorOp loadSentenceDetectorModel(@NotNull final ConfigManager config) {
        final String modelResourcePath = requireConfigValue(config, "sentenceModel");

        try (InputStream in = openResource(modelResourcePath)) {
            return new NLPSentenceDetectorOp(new SentenceModel(in));
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load sentence model %s: %s", modelResourcePath, e.getMessage()), e);
        }
    }

    /**
     * Loads the OpenNLP POS tagger model defined in the provided configuration.
     *
     * @param config analyzer configuration.
     * @return the POS model.
     */
    public static POSModel loadPosTaggerModel(@NotNull final ConfigManager config) {
        final String modelResourcePath = requireConfigValue(config, "posModel");

        try (InputStream in = openResource(modelResourcePath)) {
            return new POSModel(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load POS model %s: %s", modelResourcePath, e.getMessage()), e);
        }
    }

    /**
     * Loads the OpenNLP lemmatizer model defined in the provided configuration.
     *
     * @param config analyzer configuration.
     * @return the lemmatizer model wrapper.
     */
    public static NLPLemmatizerOp loadLemmatizerModel(@NotNull final ConfigManager config) {
        final String modelResourcePath = requireConfigValue(config, "lemmatizerModel");

        try (InputStream in = openResource(modelResourcePath)) {
            return new NLPLemmatizerOp(null, new LemmatizerModel(in));
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load lemmatizer model %s: %s", modelResourcePath, e.getMessage()), e);
        }
    }

    /**
     * Loads the OpenNLP language detector model defined in the provided configuration.
     *
     * @param config language detector configuration.
     * @return the language detector.
     */
    public static LanguageDetectorModel loadLanguageDetectorModel(@NotNull final ConfigManager config) {
        final String modelResourcePath = requireConfigValue(config, "languageDetectorModel");

        try (InputStream in = openResource(modelResourcePath)) {
            return new LanguageDetectorModel(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load language detector model %s: %s",
                            modelResourcePath, e.getMessage()), e);
        }
    }

    /**
     * Loads abbreviation expansions from {@code <language>/expandWords.csv}.
     *
     * @param language language code such as {@code fr}, {@code en}, or {@code de}.
     * @return a map from abbreviation to expansion.
     */
    public static Map<String, String> getAbbreviationMap(@NotNull final String language) {
        if (language == null) {
            throw new NullPointerException("Language code cannot be null.");
        }
        if (language.isBlank()) {
            throw new IllegalArgumentException("Language code cannot be blank.");
        }

        final Map<String, String> abbreviationMap = new HashMap<>();
        final String resourcePath = language + "/expandWords.csv";

        try (InputStream in = openResource(resourcePath);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split(",", 2);
                if (parts.length == 2) {
                    abbreviationMap.put(parts[0].trim(), parts[1].trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load abbreviation map for language %s: %s", language, e.getMessage()), e);
        }

        return abbreviationMap;
    }

    private static InputStream openResource(final String resourcePath) {
        if (resourcePath == null) {
            throw new NullPointerException("Resource path cannot be null.");
        }
        if (resourcePath.isBlank()) {
            throw new IllegalArgumentException("Resource path cannot be blank.");
        }

        final InputStream in = AnalyzerUtil.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Resource not found: " + resourcePath);
        }

        return in;
    }

    private static String requireConfigValue(final ConfigManager config, final String key) {
        if (config == null) {
            throw new NullPointerException("ConfigManager cannot be null.");
        }
        if (key == null) {
            throw new NullPointerException("Configuration key cannot be null.");
        }

        final String value = config.getString(key);
        if (value == null) {
            throw new NullPointerException("Configuration value for key '" + key + "' cannot be null.");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("Configuration value for key '" + key + "' cannot be blank.");
        }

        return value;
    }
}
