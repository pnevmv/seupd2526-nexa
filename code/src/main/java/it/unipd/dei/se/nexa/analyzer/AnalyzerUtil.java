package it.unipd.dei.se.nexa.analyzer;

import it.unipd.dei.se.nexa.utility.ConfigManager;
import opennlp.tools.langdetect.LanguageDetectorModel;
import opennlp.tools.lemmatizer.LemmatizerModel;
import opennlp.tools.postag.POSModel;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerModel;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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

/**
 * Utility methods for analyzer resource loading.
 */
public final class AnalyzerUtil {

    private static final Map<String, SynonymMap> SYNONYM_MAP_CACHE = new ConcurrentHashMap<>();

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
     * Loads abbreviation expansions from the classpath.
     * <p>
     * The project currently contains two resource layouts:
     * {@code <language>/expandWords.csv} and {@code <language>/expandWords/expandWords.csv}.
     * This loader supports both so existing French resources and the newer English/German
     * directories work without further code changes.
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
        final String resourcePath = resolveFirstExistingResource(
                language + "/expandWords.csv",
                language + "/expandWords/expandWords.csv"
        );

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

    /**
     * Loads a synonym map from the classpath resource declared in {@code synonymsFile}.
     * <p>
     * The project currently uses three source formats:
     * French entries in the form {@code headword=>synonym1,synonym2},
     * English groups in the form {@code word1, word2, word3},
     * and German groups in the form {@code word1;word2;word3}.
     * The returned synonym map canonicalizes all variants in a group to a single form.
     *
     * @param config analyzer configuration containing the {@code synonymsFile} key.
     * @param synonymNormalizerSupplier supplier of the analyzer used to normalize synonym entries.
     * @return the loaded synonym map, or {@code null} when no synonym file is configured.
     */
    public static SynonymMap loadSynonymMap(@NotNull final ConfigManager config,
                                            @NotNull final Supplier<Analyzer> synonymNormalizerSupplier) {
        if (config == null) {
            throw new NullPointerException("ConfigManager cannot be null.");
        }
        if (synonymNormalizerSupplier == null) {
            throw new NullPointerException("Synonym normalizer supplier cannot be null.");
        }

        final String resourcePath = defaultString(config.getString("synonymsFile"));
        if (resourcePath.isBlank()) {
            return null;
        }

        return SYNONYM_MAP_CACHE.computeIfAbsent(resourcePath,
                path -> buildSynonymMap(path, synonymNormalizerSupplier));
    }

    private static String resolveFirstExistingResource(final String... resourcePaths) {
        if (resourcePaths == null || resourcePaths.length == 0) {
            throw new IllegalArgumentException("At least one resource path must be provided.");
        }

        for (String resourcePath : resourcePaths) {
            if (!isBlank(resourcePath) && resourceExists(resourcePath)) {
                return resourcePath;
            }
        }

        throw new IllegalStateException("None of the configured resource paths exist.");
    }

    private static boolean resourceExists(final String resourcePath) {
        try (InputStream ignored = AnalyzerUtil.class.getClassLoader().getResourceAsStream(resourcePath)) {
            return ignored != null;
        } catch (IOException e) {
            return false;
        }
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

    private static SynonymMap buildSynonymMap(final String resourcePath,
                                              final Supplier<Analyzer> synonymNormalizerSupplier) {
        try (InputStream in = openResource(resourcePath);
             Reader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             Analyzer synonymNormalizer = requireAnalyzer(synonymNormalizerSupplier.get())) {

            final ConfigSynonymParser parser = new ConfigSynonymParser(synonymNormalizer);
            parser.parse(reader);
            return parser.build();
        } catch (IOException | ParseException e) {
            throw new IllegalStateException(
                    String.format("Unable to load synonym map %s: %s", resourcePath, e.getMessage()), e);
        }
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

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private static String defaultString(final String value) {
        return value == null ? "" : value;
    }

    private static Analyzer requireAnalyzer(final Analyzer analyzer) {
        if (analyzer == null) {
            throw new NullPointerException("Synonym normalizer analyzer cannot be null.");
        }

        return analyzer;
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
                int lineNumber = 0;

                while ((line = bufferedReader.readLine()) != null) {
                    lineNumber++;

                    final List<SynonymTerm> terms = parseLine(line);
                    if (terms.size() < 2) {
                        continue;
                    }

                    addCanonicalMappings(terms, lineNumber);
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

        private void addCanonicalMappings(final List<SynonymTerm> terms,
                                          final int lineNumber) throws IOException, ParseException {
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
            boolean addedMapping = false;
            for (String term : cleanedTerms) {
                final CharsRef analyzedTerm = analyzeSynonym(term);
                if (analyzedTerm == null || analyzedTerm.length == 0) {
                    continue;
                }

                if (canonicalKey.equals(analyzedTerm.toString())) {
                    continue;
                }

                add(analyzedTerm, canonical, true);
                addedMapping = true;
            }

            // Some source rows collapse to the same normalized term after accent folding
            // or punctuation cleanup. Those rows provide no usable mapping and can be skipped.
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

            return terms.getFirst();
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
