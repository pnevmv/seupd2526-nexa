package it.unipd.dei.se.nexa.index;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.IndexOptions;

import java.io.Reader;


public class BodyField extends Field {


    private static final FieldType TYPE = new FieldType();

    static {
        TYPE.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
        TYPE.setTokenized(true);
        TYPE.setStored(true);
        TYPE.setStoreTermVectors(true);
        TYPE.setStoreTermVectorPositions(true);
        TYPE.setStoreTermVectorOffsets(true);
        TYPE.freeze();
    }


    public BodyField(String name, String value) {
        super(name, value, TYPE);
    }


    public BodyField(String name, Reader reader) {
        super(name, reader, TYPE);
    }
}
