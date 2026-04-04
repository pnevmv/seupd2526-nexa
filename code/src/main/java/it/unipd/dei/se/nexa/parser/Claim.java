package it.unipd.dei.se.nexa.parser;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.TextField;

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

    @JsonProperty("text")
    private String text;

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
