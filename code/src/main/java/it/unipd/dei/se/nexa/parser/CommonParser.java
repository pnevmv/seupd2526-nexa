package it.unipd.dei.se.nexa.parser;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Represents a document parser for Publications.
 */
public abstract class CommonParser implements Iterator<Publication>, Iterable<Publication> {

    /**
     * Indicates whether there is another Publication to return.
     */
    protected boolean next = true;

    /**
     * The reader to be used to parse document(s).
     */
    protected final Reader in;

    /**
     * Represents the error message indicating that a Reader object cannot be null.
     */
    public static final String NULL_READER = "Reader cannot be null";

    /**
     * Creates a new document parser.
     *
     * @param in the reader to the document(s) to be parsed.
     * @throws NullPointerException if {@code in} is {@code null}.
     */
    protected CommonParser(final Reader in) {
        if (in == null) {
            throw new NullPointerException(NULL_READER);
        }
        this.in = in;
    }

    @Override
    public final @NotNull Iterator<Publication> iterator() {
        return this;
    }

    @Override
    public boolean hasNext() {
        return next;
    }

    @Override
    public final Publication next() {
        if (!next) {
            throw new NoSuchElementException("No more documents to parse.");
        }

        try {
            return parse();
        } finally {
            try {
                // we reached the end of the file
                if (!next) {
                    in.close();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to close the reader.", e);
            }
        }
    }

    /**
     * Creates a new {@code PublicationParser}.
     *
     * @param cls the class of the document parser to be instantiated.
     * @param in  the reader to the document(s) to be parsed.
     * @return a new instance of {@code PublicationParser} for the given class.
     */
    public static CommonParser create(Class<? extends CommonParser> cls, Reader in) {
        if (cls == null) {
            throw new NullPointerException("Document parser class cannot be null.");
        }
        if (in == null) {
            throw new NullPointerException(NULL_READER);
        }

        try {
            return cls.getConstructor(Reader.class).newInstance(in);
        } catch (Exception e) {
            throw new IllegalStateException(String.format("Unable to instantiate document parser %s.", cls.getName()), e);
        }
    }

    /**
     * Performs the actual parsing of the document.
     *
     * @return the parsed document.
     */
    protected abstract Publication parse();
}