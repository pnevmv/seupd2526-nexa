package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Java object representing one of the claims
 *
 */

@JsonIgnoreProperties(ignoreUnknown = true)
public class Claim {

    @JsonProperty("index")
    private int index;

    private String text;

    @JsonProperty("text")
    public void setText(String text) {
        this.text = CommonParser.cleanText(text);
    }

    @JsonProperty("pubkey")
    private int pubkey;

    @JsonProperty("translation_provider")
    private String translationProvider;

    @JsonProperty("target_language")
    private String targetLanguage;

    @JsonProperty("query_expansion_provider")
    private String queryExpansionProvider;

    @JsonProperty("query_expansion_combined")
    private boolean queryExpansionCombined;

    @Override
    public String toString() {
        return "Claim n°" + index +
                "\n Text: " + text +
                "\n Pubkey: " + pubkey;
    }

    public int getIndex() {
        return index;
    }

    public String getText() {
        return text;
    }

    public int getPubkey() {
        return pubkey;
    }

    public boolean hasMaterializedTranslation(final String expectedTargetLanguage) {
        return translationProvider != null
                && !translationProvider.isBlank()
                && targetLanguage != null
                && expectedTargetLanguage != null
                && targetLanguage.equalsIgnoreCase(expectedTargetLanguage);
    }

    public boolean hasMaterializedQueryExpansion() {
        return queryExpansionCombined
                && queryExpansionProvider != null
                && !queryExpansionProvider.isBlank();
    }
}
