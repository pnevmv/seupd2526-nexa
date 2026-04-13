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
}
