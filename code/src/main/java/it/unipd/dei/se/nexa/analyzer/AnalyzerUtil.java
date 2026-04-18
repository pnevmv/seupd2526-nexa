package it.unipd.dei.se.nexa.analyzer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.opennlp.tools.NLPLemmatizerOp;
import org.apache.lucene.analysis.opennlp.tools.NLPSentenceDetectorOp;
import org.apache.lucene.analysis.opennlp.tools.NLPTokenizerOp;
import org.apache.lucene.analysis.synonym.SynonymMap;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.CharsRefBuilder;
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
    private static final Map<String, SynonymMap> synonymMapCache = new ConcurrentHashMap<>();

    private AnalyzerUtil() {}

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
            Reader in = new BufferedReader(new FileReader(resolveResourcePath(stopList).toFile()));
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
        return loadTokenizerModel(config);
    }

    static NLPTokenizerOp loadTokenizerModel(@NotNull final ConfigManager languageConfig) {

        String modelFile = languageConfig.getString("tokenizerModel");

        if (modelFile == null)
            throw new NullPointerException("Model file name cannot be null.");

        if (modelFile.isEmpty())
            throw new IllegalArgumentException("Model file name cannot be empty.");

        NLPTokenizerOp model;

        try {

            // Get an input stream for the file containing the model
            InputStream in = Files.newInputStream(resolveResourcePath(modelFile));
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
        return loadSentenceDetectorModel(config);
    }

    static NLPSentenceDetectorOp loadSentenceDetectorModel(@NotNull final ConfigManager languageConfig) {

        String modelFile = languageConfig.getString("sentenceModel");

        if (modelFile == null)
            throw new NullPointerException("Model file name cannot be null.");

        if (modelFile.isEmpty())
            throw new IllegalArgumentException("Model file name cannot be empty.");

        NLPSentenceDetectorOp model;

        try {

            // Get an input stream for the file containing the model
            InputStream in = Files.newInputStream(resolveResourcePath(modelFile));
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
        return loadPosTaggerModel(config);
    }

    static POSModel loadPosTaggerModel(@NotNull final ConfigManager languageConfig) {

        String modelFile = languageConfig.getString("posModel");

        if (modelFile == null)
            throw new NullPointerException("Model file name cannot be null.");

        if (modelFile.isEmpty())
            throw new IllegalArgumentException("Model file name cannot be empty.");

        POSModel model;

        try {

            // Get an input stream for the file containing the model
            InputStream in = Files.newInputStream(resolveResourcePath(modelFile));
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
        return loadLemmatizerModel(config);
    }

    static NLPLemmatizerOp loadLemmatizerModel(@NotNull final ConfigManager languageConfig) {

        String modelFile = languageConfig.getString("lemmatizerModel");

        if (modelFile == null)
            throw new NullPointerException("Model file name cannot be null.");

        if (modelFile.isEmpty())
            throw new IllegalArgumentException("Model file name cannot be empty.");

        NLPLemmatizerOp model;

        try {

            // Get an input stream for the file containing the model
            InputStream in = Files.newInputStream(resolveResourcePath(modelFile));
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

    public static Map<String, String> getAbbreviationMap(String language) {
        Map<String, String> abbreviationMap = new HashMap<>();
        Path filePath = resolveFirstReadable(
                Paths.get("src/main/resources", language, "expandWords.csv"),
                Paths.get("src/main/resources", language, "expandWords", "expandWords.csv"),
                Paths.get("code/src/main/resources", language, "expandWords.csv"),
                Paths.get("code/src/main/resources", language, "expandWords", "expandWords.csv")
        );

        try (BufferedReader br = new BufferedReader(new FileReader(filePath.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {

                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                String[] parts = line.split(",");

                if (parts.length == 2) {
                    String abbreviation = parts[0].trim();
                    String expansion = parts[1].trim();

                    abbreviationMap.put(abbreviation, expansion);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return abbreviationMap;
    }

    static SynonymMap loadSynonymMap(@NotNull final ConfigManager languageConfig,
                                     @NotNull final Supplier<Analyzer> synonymNormalizerSupplier) {

        final String synonymsFile = languageConfig.getString("synonymsFile");
        if (synonymsFile == null || synonymsFile.isBlank()) {
            return null;
        }

        final Path synonymsPath = resolveResourcePath(synonymsFile);
        return synonymMapCache.computeIfAbsent(synonymsPath.toString(),
                ignored -> buildSynonymMap(synonymsPath, synonymNormalizerSupplier));
    }

    private static Path resolveResourcePath(final String resourcePath) {
        return resolveFirstReadable(
                Paths.get(resourcePath),
                Paths.get("code").resolve(resourcePath)
        );
    }

    private static Path resolveFirstReadable(final Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.isReadable(candidate)) {
                return candidate;
            }
        }

        return candidates[0];
    }

    private static SynonymMap buildSynonymMap(final Path synonymsPath,
                                              final Supplier<Analyzer> synonymNormalizerSupplier) {
        try (Reader reader = new BufferedReader(new FileReader(synonymsPath.toFile()));
             Analyzer synonymNormalizer = synonymNormalizerSupplier.get()) {

            ConfigSynonymParser parser = new ConfigSynonymParser(synonymNormalizer);
            parser.parse(reader);
            return parser.build();
        } catch (IOException | ParseException e) {
            throw new IllegalStateException(
                    String.format("Unable to load synonym map %s: %s", synonymsPath, e.getMessage()), e);
        }
    }

    private static final class ConfigSynonymParser extends SynonymMap.Parser {

        private static final Pattern PARENTHETICAL_PATTERN = Pattern.compile("\\([^)]*\\)");
        private static final Pattern MULTISPACE_PATTERN = Pattern.compile("\\s+");
        private static final Pattern LEADING_TRAILING_PUNCTUATION =
                Pattern.compile("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$");

        private ConfigSynonymParser(final Analyzer analyzer) {
            super(true, analyzer);
        }

        @Override
        public void parse(final Reader reader) throws IOException, ParseException {
            try (BufferedReader bufferedReader = new BufferedReader(reader)) {
                String line;

                while ((line = bufferedReader.readLine()) != null) {
                    final List<SynonymTerm> terms = parseLine(line);
                    if (terms.size() < 2) {
                        continue;
                    }

                    addCanonicalMappings(terms);
                }
            }
        }

        private List<SynonymTerm> parseLine(final String rawLine) {
            if (rawLine == null) {
                return List.of();
            }

            final String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                return List.of();
            }

            if (line.contains("=>")) {
                return parseFrenchLine(line);
            }

            if (line.contains(";")) {
                return parseDelimitedLine(line, ";");
            }

            if (line.contains(",")) {
                return parseDelimitedLine(line, ",");
            }

            return List.of();
        }

        private List<SynonymTerm> parseFrenchLine(final String line) {
            final String[] parts = line.split("=>", 2);
            if (parts.length != 2) {
                return List.of();
            }

            final List<SynonymTerm> terms = new ArrayList<>();
            terms.add(new SynonymTerm(parts[0], true));
            for (String synonym : parts[1].split(",")) {
                terms.add(new SynonymTerm(synonym, false));
            }

            return terms;
        }

        private List<SynonymTerm> parseDelimitedLine(final String line, final String delimiter) {
            final List<SynonymTerm> terms = new ArrayList<>();
            for (String term : line.split(Pattern.quote(delimiter))) {
                terms.add(new SynonymTerm(term, containsCanonicalMarker(term)));
            }
            return terms;
        }

        private void addCanonicalMappings(final List<SynonymTerm> terms) throws IOException {
            final List<String> cleanedTerms = new ArrayList<>();
            final Set<String> seenTerms = new LinkedHashSet<>();

            final SynonymTerm canonicalTerm = chooseCanonicalTerm(terms);
            final String cleanedCanonical = cleanSynonymEntry(canonicalTerm.rawValue());
            if (!cleanedCanonical.isBlank()) {
                seenTerms.add(cleanedCanonical);
                cleanedTerms.add(cleanedCanonical);
            }

            for (SynonymTerm term : terms) {
                final String cleaned = cleanSynonymEntry(term.rawValue());
                if (!cleaned.isBlank() && seenTerms.add(cleaned)) {
                    cleanedTerms.add(cleaned);
                }
            }

            if (cleanedTerms.size() < 2) {
                return;
            }

            final CharsRef canonical = analyzeSynonym(cleanedCanonical);
            if (canonical == null || canonical.length == 0) {
                return;
            }

            final String canonicalKey = canonical.toString();
            for (String term : cleanedTerms) {
                final CharsRef analyzedTerm = analyzeSynonym(term);
                if (analyzedTerm == null || analyzedTerm.length == 0) {
                    continue;
                }

                if (canonicalKey.equals(analyzedTerm.toString())) {
                    continue;
                }

                add(analyzedTerm, canonical, true);
            }
        }

        private CharsRef analyzeSynonym(final String synonymTerm) throws IOException {
            final CharsRefBuilder builder = new CharsRefBuilder();
            final CharsRef analyzed;

            try {
                analyzed = analyze(synonymTerm, builder);
            } catch (IllegalArgumentException e) {
                return null;
            }

            if (analyzed == null) {
                return null;
            }

            return CharsRef.deepCopyOf(analyzed);
        }

        private SynonymTerm chooseCanonicalTerm(final List<SynonymTerm> terms) {
            for (SynonymTerm term : terms) {
                if (term.isCanonical()) {
                    return term;
                }
            }

            return terms.get(0);
        }

        private boolean containsCanonicalMarker(final String rawValue) {
            return rawValue != null && rawValue.toLowerCase(Locale.ROOT).contains("hauptform");
        }

        private String cleanSynonymEntry(final String rawValue) {
            if (rawValue == null) {
                return "";
            }

            String cleaned = rawValue.replace('\u00A0', ' ');
            cleaned = cleaned.replace("...", " ");
            cleaned = PARENTHETICAL_PATTERN.matcher(cleaned).replaceAll(" ");
            cleaned = LEADING_TRAILING_PUNCTUATION.matcher(cleaned).replaceAll("");
            cleaned = MULTISPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();

            return cleaned;
        }
    }

    private record SynonymTerm(String rawValue, boolean isCanonical) {
    }
}
