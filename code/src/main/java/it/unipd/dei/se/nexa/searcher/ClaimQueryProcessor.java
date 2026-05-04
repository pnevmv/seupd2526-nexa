package it.unipd.dei.se.nexa.searcher;

import it.unipd.dei.se.nexa.parser.Claim;
import it.unipd.dei.se.nexa.utility.LanguageDetectionUtil;
import it.unipd.dei.se.nexa.utility.QueryExpansionUtil;
import it.unipd.dei.se.nexa.utility.TranslationUtil;

public final class ClaimQueryProcessor {

    private final String claimsLanguage;
    private final boolean translateClaimsToEnglish;
    private final String translationTargetLanguage;

    public ClaimQueryProcessor(final String claimsLanguage) {
        this.claimsLanguage = claimsLanguage;
        this.translateClaimsToEnglish = TranslationUtil.shouldTranslateClaimsToEnglish();
        this.translationTargetLanguage = TranslationUtil.getTranslationTargetLanguage();
    }

    public ProcessedClaimQuery process(final Claim claim) {
        if (claim == null) {
            return new ProcessedClaimQuery(null, null, false);
        }

        String text = claim.getText();
        boolean translated = false;

        if (text == null || text.isBlank()) {
            return new ProcessedClaimQuery(claim, text, false);
        }

        if (translateClaimsToEnglish && !claim.hasMaterializedTranslation(translationTargetLanguage)) {
            final String sourceLanguage = claimsLanguage != null
                    ? claimsLanguage
                    : LanguageDetectionUtil.detectClaimLanguage(claim);
            if (shouldTranslateClaim(sourceLanguage, translationTargetLanguage)) {
                text = TranslationUtil.translateClaimText(text, sourceLanguage, translationTargetLanguage);
                translated = true;
            }
        }

        if (!claim.hasMaterializedQueryExpansion()) {
            text = QueryExpansionUtil.expandQuery(text);
        }

        return new ProcessedClaimQuery(claim, text, translated);
    }

    public boolean translatesClaimsToEnglish() {
        return translateClaimsToEnglish;
    }

    public String translationTargetLanguage() {
        return translationTargetLanguage;
    }

    public String claimsLanguage() {
        return claimsLanguage;
    }

    private static boolean shouldTranslateClaim(final String sourceLanguage, final String targetLanguage) {
        return sourceLanguage != null
                && !sourceLanguage.isBlank()
                && !LanguageDetectionUtil.UNKNOWN.equalsIgnoreCase(sourceLanguage)
                && !sourceLanguage.equalsIgnoreCase(targetLanguage);
    }
}
