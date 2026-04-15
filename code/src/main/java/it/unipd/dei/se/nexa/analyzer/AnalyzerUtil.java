package it.unipd.dei.se.nexa.analyzer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.opennlp.tools.NLPLemmatizerOp;
import org.apache.lucene.analysis.opennlp.tools.NLPSentenceDetectorOp;
import org.apache.lucene.analysis.opennlp.tools.NLPTokenizerOp;
import org.jetbrains.annotations.NotNull;

import it.unipd.dei.se.nexa.utility.ConfigManager;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerModel;

/**
 * Utility methods for analyzers.
 */
public final class AnalyzerUtil {

    private AnalyzerUtil() {
    }

    /**
     * Loads a stop list from a file and returns it as a CharArraySet.
     *
     * @param stopList the file path of the stop list.
     * @return a CharArraySet containing the stop words loaded from the file.
     */
    public static CharArraySet loadStopList(@NotNull final String stopList) {
        try (InputStream in = openResource(stopList);
             Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return WordlistLoader.getWordSet(reader);
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load the stop list %s: %s", stopList, e.getMessage()), e);
        }
    }


    private static InputStream openResource(String resourcePath) {
        if (resourcePath == null) {
            throw new NullPointerException("Resource path cannot be null.");
        }
        if (resourcePath.isEmpty()) {
            throw new IllegalArgumentException("Resource path cannot be empty.");
        }

        InputStream in = AnalyzerUtil.class.getClassLoader().getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("Resource not found: " + resourcePath);
        }

        return in;
    }

    static NLPTokenizerOp loadTokenizerModel(@NotNull final ConfigManager config) {
        String modelFile = config.getString("tokenizerModel");

        try (InputStream in = openResource(modelFile)) {
            return new NLPTokenizerOp(new TokenizerModel(in));
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load tokenizer model %s: %s", modelFile, e.getMessage()), e);
        }
    }

    static NLPSentenceDetectorOp loadSentenceDetectorModel(@NotNull final ConfigManager config) {
        String modelFile = config.getString("sentenceModel");

        try (InputStream in = openResource(modelFile)) {
            return new NLPSentenceDetectorOp(new SentenceModel(in));
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load sentence model %s: %s", modelFile, e.getMessage()), e);
        }
    }

    static POSModel loadPosTaggerModel(@NotNull final ConfigManager config) {
        String modelFile = config.getString("posModel");

        try (InputStream in = openResource(modelFile)) {
            return new POSModel(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load POS model %s: %s", modelFile, e.getMessage()), e);
        }
    }

    static NLPLemmatizerOp loadLemmatizerModel(@NotNull final ConfigManager config) {
        String modelFile = config.getString("lemmatizerModel");

        try (InputStream in = openResource(modelFile)) {
            return new NLPLemmatizerOp(null, new LemmatizerModel(in));
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load lemmatizer model %s: %s", modelFile, e.getMessage()), e);
        }
    }

    public static Map<String, String> getAbbreviationMap(@NotNull final String language) {
        Map<String, String> abbreviationMap = new HashMap<>();
        String resourcePath = language + "/expandWords.csv";

        try (InputStream in = openResource(resourcePath);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
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
}