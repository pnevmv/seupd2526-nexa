package it.unipd.dei.se.nexa.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import it.unipd.dei.se.nexa.indexer.BodyField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.VectorSimilarityFunction;

/**
 * Java object representing one of the publication
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Publication {

    public static final String FIELD_PUBKEY = "pubkey";
    public static final String FIELD_LANGUAGE = "language";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_ABSTRACT = "abstract";
    public static final String FIELD_VENUE = "venue";
    public static final String FIELD_AUTHORS = "authors";

    private static final String UNKNOWN_LANGUAGE = "unknown";

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
        return toLuceneDocument(UNKNOWN_LANGUAGE);
    }

    public Document toLuceneDocument(final String languageCode) {
        final String normalizedLanguage = normalizeLanguageCode(languageCode);
        final String safeTitle = safeString(title);
        final String safeAbstract = safeString(abstract_text);
        final String safeVenue = safeString(venue);
        final String safeAuthors = safeString(authors);

        Document doc = new Document();
        doc.add(new StoredField(FIELD_PUBKEY, pubkey));
        doc.add(new IntPoint(FIELD_PUBKEY, pubkey));
        doc.add(new StringField(FIELD_LANGUAGE, normalizedLanguage, Field.Store.YES));

        // Keep the legacy generic fields for compatibility with existing consumers.
        doc.add(new TextField(FIELD_TITLE, safeTitle, Field.Store.YES));
        doc.add(new BodyField(FIELD_ABSTRACT, safeAbstract));

        // Add language-specific fields so Lucene can route them through the matching analyzer.
        doc.add(new TextField(getLocalizedFieldName(FIELD_TITLE, normalizedLanguage), safeTitle, Field.Store.NO));
        doc.add(new BodyField(getLocalizedFieldName(FIELD_ABSTRACT, normalizedLanguage), safeAbstract));

        doc.add(new TextField(FIELD_VENUE, safeVenue, Field.Store.NO));
        doc.add(new TextField(FIELD_AUTHORS, safeAuthors, Field.Store.NO));

        if (embedding != null && embedding.length > 0) {
            doc.add(new KnnFloatVectorField("pub_vector", embedding, VectorSimilarityFunction.COSINE));
        }

        return doc;
    }

    public static String getLocalizedFieldName(final String baseFieldName, final String languageCode) {
        return baseFieldName + "_" + normalizeLanguageCode(languageCode);
    }

    private static String normalizeLanguageCode(final String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return UNKNOWN_LANGUAGE;
        }
        return languageCode.toLowerCase();
    }

    private static String safeString(final String value) {
        return value == null ? "" : value;
    }
}
