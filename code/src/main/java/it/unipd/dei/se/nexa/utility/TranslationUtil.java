package it.unipd.dei.se.nexa.utility;

import it.unipd.dei.se.nexa.parser.CommonParser;
import it.unipd.dei.se.nexa.parser.Publication;

import java.io.IOException;
import java.util.Locale;

public final class TranslationUtil {

    private static final String DOCUMENT_TRANSLATION_KEY = "translateNonEnglishPublicationsToEnglish";
    private static final String CLAIM_TRANSLATION_KEY = "translateNonEnglishClaimsToEnglish";
    private static final String PROVIDER_KEY = "translationProvider";
    private static final String COMMAND_KEY = "translationCommand";
    private static final String SERVICE_URL_KEY = "translationServiceUrl";
    private static final String TARGET_LANGUAGE_KEY = "translationTargetLanguage";

    private static final String PROVIDER_NONE = "none";
    private static final String PROVIDER_COMMAND = "command";
    private static final String PROVIDER_GEMMA = "gemma";

    private static final ConfigManager GLOBAL_CONFIG = ConfigManager.getGlobalConfig();
    private static final TranslationService TRANSLATION_SERVICE = createTranslationService();

    private TranslationUtil() {
    }

    public static boolean shouldTranslatePublicationsToEnglish() {
        return Boolean.TRUE.equals(GLOBAL_CONFIG.getBool(DOCUMENT_TRANSLATION_KEY));
    }

    public static boolean shouldTranslateClaimsToEnglish() {
        return Boolean.TRUE.equals(GLOBAL_CONFIG.getBool(CLAIM_TRANSLATION_KEY));
    }

    public static String getTranslationTargetLanguage() {
        final String configuredTarget = GLOBAL_CONFIG.getString(TARGET_LANGUAGE_KEY);
        return configuredTarget == null || configuredTarget.isBlank()
                ? LanguageDetectionUtil.ENGLISH
                : configuredTarget.toLowerCase(Locale.ROOT);
    }

    public static Publication translatePublication(final Publication publication,
                                                   final String sourceLanguage,
                                                   final String targetLanguage) {
        if (publication == null || !shouldTranslate(sourceLanguage, targetLanguage)) {
            return publication;
        }

        return new Publication(
                publication.getPubkey(),
                cleanTranslatedText(translateText(publication.getTitle(), sourceLanguage, targetLanguage)),
                translateAbstract(publication.getAbstract(), sourceLanguage, targetLanguage),
                publication.getVenue(),
                publication.getAuthors()
        );
    }

    public static String translateClaimText(final String text,
                                            final String sourceLanguage,
                                            final String targetLanguage) {
        if (!shouldTranslate(sourceLanguage, targetLanguage)) {
            return text;
        }

        return cleanTranslatedText(translateText(text, sourceLanguage, targetLanguage));
    }

    private static String translateAbstract(final String text,
                                            final String sourceLanguage,
                                            final String targetLanguage) {
        if ("#".equals(text)) {
            return text;
        }

        return cleanScientificTranslation(translateText(text, sourceLanguage, targetLanguage));
    }

    private static boolean shouldTranslate(final String sourceLanguage, final String targetLanguage) {
        if (sourceLanguage == null || sourceLanguage.isBlank()) {
            return false;
        }

        final String normalizedSource = sourceLanguage.toLowerCase(Locale.ROOT);
        final String normalizedTarget = targetLanguage == null
                ? LanguageDetectionUtil.ENGLISH
                : targetLanguage.toLowerCase(Locale.ROOT);

        return !LanguageDetectionUtil.UNKNOWN.equals(normalizedSource)
                && !normalizedSource.equals(normalizedTarget);
    }

    private static String translateText(final String text,
                                        final String sourceLanguage,
                                        final String targetLanguage) {
        if (text == null || text.isBlank()) {
            return text;
        }

        try {
            return TRANSLATION_SERVICE.translate(text, sourceLanguage, targetLanguage);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to translate text from "
                    + sourceLanguage + " to " + targetLanguage + ".", e);
        }
    }

    private static String cleanTranslatedText(final String text) {
        return CommonParser.cleanText(text);
    }

    private static String cleanScientificTranslation(final String text) {
        return CommonParser.cleanScientificText(text);
    }

    private static TranslationService createTranslationService() {
        final String provider = GLOBAL_CONFIG.getString(PROVIDER_KEY);
        if (provider == null || provider.isBlank() || PROVIDER_NONE.equalsIgnoreCase(provider)) {
            if (shouldTranslatePublicationsToEnglish() || shouldTranslateClaimsToEnglish()) {
                throw new IllegalStateException("Translation is enabled but translationProvider is not configured.");
            }
            return new NoOpTranslationService();
        }

        if (PROVIDER_COMMAND.equalsIgnoreCase(provider)) {
            return new CommandLineTranslationService(GLOBAL_CONFIG.getString(COMMAND_KEY));
        }
        if (PROVIDER_GEMMA.equalsIgnoreCase(provider)) {
            return new GemmaTranslationService(GLOBAL_CONFIG.getString(SERVICE_URL_KEY));
        }

        throw new IllegalArgumentException("Unsupported translationProvider: " + provider);
    }
}
