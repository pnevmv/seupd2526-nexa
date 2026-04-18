package it.unipd.dei.se.nexa.parser;

import org.apache.lucene.document.*;
import it.unipd.dei.se.nexa.indexer.BodyField;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.lucene.index.VectorSimilarityFunction;

/**
 * Java object representing one of the publication
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Publication {

    @JsonProperty("pubkey")
    private int pubkey;

    @JsonProperty("title")
    private String title;

    @JsonProperty("abstract")
    private String abstract_text;

    @JsonProperty("venue")
    private String venue;

    @JsonProperty("authors")
    private String authors;

    private float[] embedding;

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    /** Default constructor for Jackson */
    public Publication() {}

    /** All-args constructor for manual parsing */
    public Publication(int pubkey, String title, String abstract_text, String venue, String authors) {
        this.pubkey = pubkey;
        this.title = title;
        this.abstract_text = abstract_text;
        this.venue = venue;
        this.authors = authors;
    }

    @Override
    public String toString() {
        return "Publication n°" + pubkey +
                "\n Title : " + title +
                "\n Abstract : " + abstract_text +
                "\n Venue : " + venue +
                "\n Authors : " + authors;
    }

    public int getPubkey() { return pubkey; }
    public String getTitle() { return title; }
    public String getAbstract() { return abstract_text; }
    public String getVenue() { return venue; }
    public String getAuthors() { return authors; }

    public Document toLuceneDocument() {
        Document doc = new Document();
        doc.add(new StoredField("pubkey", pubkey));
        doc.add(new IntPoint("pubkey", pubkey));
        doc.add(new TextField("title", title, Field.Store.YES));
        doc.add(new BodyField("abstract", abstract_text));
        doc.add(new TextField("venue", venue, Field.Store.NO));
        doc.add(new TextField("authors", authors, Field.Store.NO));

        if (embedding != null && embedding.length > 0) {
            doc.add(new KnnFloatVectorField("pub_vector", embedding, VectorSimilarityFunction.COSINE));
        }

        return doc;
    }
}