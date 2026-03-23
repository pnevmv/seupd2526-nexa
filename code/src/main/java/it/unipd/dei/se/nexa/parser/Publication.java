package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Java object representing one of the publication
 *
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Publication {

    /** The publication key **/
    @JsonProperty("pubkey")
    private int pubkey;

    /** The publication title **/
    @JsonProperty("title")
    private String title;

    /** The publication abstract **/
    @JsonProperty("abstract")
    private String abstract_text;

    /** The publication venue **/
    @JsonProperty("venue")
    private String venue;

    /** The publication authors **/
    @JsonProperty("authors")
    private String authors;

    /**
     * Returns a string representation of this object
     * @return a string representation of this object
     */
    @Override
    public String toString() {
        return "Publication n°" + pubkey +
        "\n Title : " + title +
        "\n Abstract : " + abstract_text +
        "\n Venue : " + venue + 
        "\n Authors : " + authors;
    }

    /**
     * Returns the pubkey
     * @return the pubkey
     */
    public Object getPubkey() {
        return pubkey;
    }

    /**
     * Returns the title
     * 
     * @return the title
     */
    public Object getTitle() {
        return title;
    }

    /**
     * Returns the abstract
     * 
     * @return the abstract
     */
    public Object getAbstract() {
        return abstract_text;
    }

    /**
     * Returns the venue
     * 
     * @return the venue
     */
    public Object getVenue() {
        return venue;
    }

    /**
     * Returns the authors
     * 
     * @return the authors
     */
    public Object getAuthors() {
        return authors;
    }
}
