package it.unipd.dei.se.nexa.utility;

import it.unipd.dei.se.nexa.analyzer.AnalyzerUtil;
import it.unipd.dei.se.nexa.parser.Claim;
import it.unipd.dei.se.nexa.parser.Publication;
import opennlp.tools.langdetect.Language;
import opennlp.tools.langdetect.LanguageDetectorME;
import opennlp.tools.langdetect.LanguageDetectorModel;

public final class LanguageDetectionUtil {

    public static final String ENGLISH = "en";
    public static final String FRENCH = "fr";
    public static final String GERMAN = "de";
    public static final String UNKNOWN = "unknown";

    private static final double MIN_CONFIDENCE = 0.60;

    private static final LanguageDetectorModel LANGUAGE_DETECTOR_MODEL =
            AnalyzerUtil.loadLanguageDetectorModel(ConfigManager.getGlobalConfig());

    private static final LanguageDetectorME DETECTOR =
            new LanguageDetectorME(LANGUAGE_DETECTOR_MODEL);

    private LanguageDetectionUtil() {}

    public static String detectLanguage(final String text) {
        if (text == null || text.isBlank()) {
            return UNKNOWN;
        }

        final Language bestPrediction = DETECTOR.predictLanguage(text);

        if (bestPrediction == null || bestPrediction.getLang() == null) {
            return UNKNOWN;
        }

        if (bestPrediction.getConfidence() < MIN_CONFIDENCE) {
            return UNKNOWN;
        }

        return mapLanguageCode(bestPrediction.getLang());
    }

    public static String detectPublicationLanguage(final Publication publication) {
        if (publication == null) {
            return UNKNOWN;
        }

        final StringBuilder detectionText = new StringBuilder();

        if (publication.getTitle() != null && !publication.getTitle().isBlank()) {
            detectionText.append(publication.getTitle());
        }

        if (publication.getAbstract() != null
                && !publication.getAbstract().isBlank()
                && !"#".equals(publication.getAbstract())) {
            if (detectionText.length() > 0) {
                detectionText.append(' ');
            }
            detectionText.append(publication.getAbstract());
        }

        return detectLanguage(detectionText.toString());
    }

    public static String detectClaimLanguage(final Claim claim) {
        if (claim == null) {
            return UNKNOWN;
        }

        return detectLanguage(claim.getText());
    }

    private static String mapLanguageCode(final String rawLanguageCode) {
        if (rawLanguageCode == null) {
            return UNKNOWN;
        }

        return switch (rawLanguageCode.toLowerCase()) {
            case "eng", "en" -> ENGLISH;
            case "fra", "fre", "fr" -> FRENCH;
            case "deu", "ger", "de" -> GERMAN;
            default -> UNKNOWN;
        };
    }
}
