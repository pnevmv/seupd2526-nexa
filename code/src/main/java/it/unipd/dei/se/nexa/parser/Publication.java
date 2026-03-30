package it.unipd.dei.se.nexa.parser;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.TextField;

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
    public String getTitle() {
        return title;
    }

    /**
     * Returns the abstract
     * 
     * @return the abstract
     */
    public String getAbstract() {
        return abstract_text;
    }

    /**
     * Returns the venue
     * 
     * @return the venue
     */
    public String getVenue() {
        return venue;
    }

    /**
     * Returns the authors
     * 
     * @return the authors
     */
    public String getAuthors() {
        return authors;
    }

    public Document toLuceneDocument() {
        Document doc = new Document();

        doc.add(new IntField("pubkey", pubkey, Field.Store.YES));
        doc.add(new TextField("title", title, Field.Store.NO));
        doc.add(new TextField("abstract", abstract_text, Field.Store.NO));
        doc.add(new TextField("venue", venue, Field.Store.NO));
        doc.add(new TextField("authors", authors, Field.Store.NO));
        return doc;
    }
}
