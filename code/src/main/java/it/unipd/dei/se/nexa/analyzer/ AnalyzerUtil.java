package it.unipd.dei.se.nexa.analyzer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
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
 * The AnalyzerUtil class provides utility methods for analyzing text data.
 * It includes functionality for loading stop lists, which are collections.
 */
public class AnalyzerUtil {

    private static final ConfigManager config = ConfigManager.getInstance("fr");

    private AnalyzerUtil() {
    }

    /**
     * Loads a stop list from a file and returns it as a CharArraySet.
     *
     * @param stopList The file path of the stop list.
     * @return A CharArraySet containing the stop words loaded from the file.
     * @throws IllegalArgumentException if the stop list file path is empty.
     * @throws IllegalStateException    if an error occurs while loading the stop
     *                                  list file.
     */
    public static CharArraySet loadStopList(@NotNull final String stopList) {
        if (stopList.isEmpty())
            throw new IllegalArgumentException("stopList cant be empty");
        CharArraySet stopWordsList;
        try {
            Reader in = new BufferedReader(new FileReader(stopList));
            stopWordsList = WordlistLoader.getWordSet(in);
            in.close();
        } catch (IOException e) {
            throw new IllegalStateException(
                    String.format("Unable to load the stop list %s: %s", stopList, e.getMessage()), e);
        }
        return stopWordsList;
    }

    /**
     * Loads the required Apache OpenNLP tokenizer model among those available in
     * the {@code resources} folder.
     *
     * @return the required Apache OpenNLP model.
     * @throws IllegalStateException if there is any issue while loading the model.
     */
    static NLPTokenizerOp loadTokenizerModel() {

        String modelFile = config.getString("tokenizerModel");

        if (modelFile == null)
            throw new NullPointerException("Model file name cannot be null.");

        if (modelFile.isEmpty())
            throw new IllegalArgumentException("Model file name cannot be empty.");

        NLPTokenizerOp model;

        try {

            // Get an input stream for the file containing the model
            InputStream in = Files.newInputStream(Paths.get(modelFile));
            // Load the model
            model = new NLPTokenizerOp(new TokenizerModel(in));
            // Close the file
            in.close();

        } catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to load the model %s: %s", modelFile, e.getMessage()),
                    e);
        }

        return model;
    }

    /**
     * Loads the required Apache OpenNLP sentence detector model among those
     * available in the {@code resources} folder.
     *
     * @return the required Apache OpenNLP model.
     * @throws IllegalStateException if there is any issue while loading the model.
     */
    static NLPSentenceDetectorOp loadSentenceDetectorModel() {

        String modelFile = config.getString("sentenceModel");

        if (modelFile == null)
            throw new NullPointerException("Model file name cannot be null.");

        if (modelFile.isEmpty())
            throw new IllegalArgumentException("Model file name cannot be empty.");

        NLPSentenceDetectorOp model;

        try {

            // Get an input stream for the file containing the model
            InputStream in = Files.newInputStream(Paths.get(modelFile));
            // Load the model
            model = new NLPSentenceDetectorOp(new SentenceModel(in));
            // Close the file
            in.close();

        } catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to load the model %s: %s", modelFile, e.getMessage()),
                    e);
        }

        return model;
    }

    /**
     * Loads the required Apache OpenNLP POS tagger model among those available in
     * the {@code resources} folder.
     *
     * @return the required Apache OpenNLP model.
     * @throws IllegalStateException if there is any issue while loading the model.
     */
    static POSModel loadPosTaggerModel() {

        String modelFile = config.getString("posModel");

        if (modelFile == null)
            throw new NullPointerException("Model file name cannot be null.");

        if (modelFile.isEmpty())
            throw new IllegalArgumentException("Model file name cannot be empty.");

        POSModel model;

        try {

            // Get an input stream for the file containing the model
            InputStream in = Files.newInputStream(Paths.get(modelFile));
            // Load the model
            model = new POSModel(in);
            // Close the file
            in.close();

        } catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to load the model %s: %s", modelFile, e.getMessage()),
                    e);
        }

        return model;
    }

    /**
     * Loads the required Apache OpenNLP lemmatizer model among those available in
     * the {@code resources} folder.
     *
     * @return the required Apache OpenNLP model.
     * @throws IllegalStateException if there is any issue while loading the model.
     */
    static NLPLemmatizerOp loadLemmatizerModel() {

        String modelFile = config.getString("lemmatizerModel");

        if (modelFile == null)
            throw new NullPointerException("Model file name cannot be null.");

        if (modelFile.isEmpty())
            throw new IllegalArgumentException("Model file name cannot be empty.");

        NLPLemmatizerOp model;

        try {

            // Get an input stream for the file containing the model
            InputStream in = Files.newInputStream(Paths.get(modelFile));
            // Load the model
            model = new NLPLemmatizerOp(null, new LemmatizerModel(in));
            // Close the file
            in.close();

        } catch (IOException e) {
            throw new IllegalStateException(String.format("Unable to load the model %s: %s", modelFile, e.getMessage()),
                    e);
        }

        return model;
    }

    public static Map<String, String> getAbbreviationMap() {
        Map<String, String> abbreviationMap = new HashMap<>();
        String filePath = "src/main/java/resources/expandWords.csv";

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {

                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                String[] parts = line.split(",");

                if (parts.length == 2) {
                    String abbreviation = parts[0].trim();
                    String expansion = parts[1].trim();

                    // Aggiungi alla mappa
                    abbreviationMap.put(abbreviation, expansion);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return abbreviationMap;
    }
}