package it.unipd.dei.se.nexa.parser;

import org.jetbrains.annotations.NotNull;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

/**
 * Represents a document parser for Publications.
 */
public abstract class CommonParser implements Iterator<Publication>, Iterable<Publication> {

    protected static final Pattern HTML = Pattern.compile("<[^>]*>");
    protected static final Pattern URL = Pattern.compile("https?://[\\w./]+\\w+");
    protected static final Pattern CITATION_SQUARE = Pattern.compile("\\[\\s*\\d+(?:(?:\\s*,\\s*|\\s*-\\s*|\\s*–\\s*)\\d+)*\\s*]");
    protected static final Pattern CITATION_AUTHORS = Pattern.compile("\\([A-Za-z\\s.,]+et al\\.?.*?\\)");
    protected static final Pattern SECTIONS = Pattern.compile("(?i)\\b(Abstract|Objective|Design|Setting|Participants|Results|Conclusions|Methods|Background)\\b\\s*:?");
    protected static final Pattern EMOJI = Pattern.compile("[\\x{1F600}-\\x{1F64F}\\x{2700}-\\x{27BF}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F900}-\\x{1F9FF}\\x{2600}-\\x{26FF}]");
    protected static final Pattern TWITTER_MENTION = Pattern.compile("@\\w+");

    public static String cleanText(String input) {
        if (input == null || input.isEmpty()) return input;
        input = Normalizer.normalize(input, Normalizer.Form.NFC);
        input = StringEscapeUtils.unescapeHtml4(input);
        input = HTML.matcher(input).replaceAll(" ");
        input = URL.matcher(input).replaceAll(" ");
        input = CITATION_SQUARE.matcher(input).replaceAll(" ");
        input = CITATION_AUTHORS.matcher(input).replaceAll(" ");
        input = SECTIONS.matcher(input).replaceAll(" ");
        input = input.replace('·', '.');
        input = input.replaceAll("[±≥°]", " ");
        input = EMOJI.matcher(input).replaceAll(" ");
        input = TWITTER_MENTION.matcher(input).replaceAll(" ");
        input = input.replaceAll("\\s+", " ").trim();

        return input;
    }

    public static String cleanScientificText(String input) {
        return cleanText(input);
    }

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
