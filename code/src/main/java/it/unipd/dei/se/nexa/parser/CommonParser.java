package it.unipd.dei.se.nexa.parser;

import org.jetbrains.annotations.NotNull;
import org.apache.commons.text.StringEscapeUtils;

import java.io.IOException;
import java.io.Reader;
import java.util.Iterator;
import java.text.Normalizer;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;

public abstract class CommonParser implements Iterator<Publication>, Iterable<Publication> {

    protected static final Pattern HTML = Pattern.compile("<[^>]*>");
    protected static final Pattern URL = Pattern.compile("https?://\\S+");
    protected static final Pattern TWITTER_MENTION = Pattern.compile("@\\w+");
    protected static final Pattern CITATION_AUTHORS = Pattern.compile("\\([A-Za-z\\s.,]+et al\\.?.*?\\)");
    protected static final Pattern SECTIONS = Pattern.compile("(?i)\\b(Abstract|Objective|Design|Setting|Participants|Results|Conclusions|Methods|Background)\\b\\s*:?");
    protected static final Pattern CITATION_SQUARE =
            Pattern.compile("\\[\\s*\\d+(?:(?:\\s*,\\s*|\\s*-\\s*|\\s*–\\s*)\\d+)*\\s*]");
    protected static final Pattern CITATION_ET_AL =
            Pattern.compile("\\([^()]{0,80}?\\bet al\\.?(?:,\\s*\\d{4})?[^()]{0,80}?\\)");
    protected static final Pattern EN_SECTION_HEADERS =
            Pattern.compile("(?im)^\\s*(Abstract|Objective|Objectives|Design|Setting|Participants|Results|Conclusions|Methods|Background)\\s*:?\\s*");
    protected static final Pattern EMOJI =
            Pattern.compile("[\\x{1F600}-\\x{1F64F}\\x{2700}-\\x{27BF}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F900}-\\x{1F9FF}\\x{2600}-\\x{26FF}]");

    public static final String NULL_READER = "Reader cannot be null";

    protected final Reader in;

    protected boolean hasNextPublication = true;

    protected CommonParser(final Reader in) {
        if (in == null) {
            throw new NullPointerException(NULL_READER);
        }
        this.in = in;
    }

    public static String cleanText(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }

        input = StringEscapeUtils.unescapeHtml4(input);
        input = HTML.matcher(input).replaceAll(" ");
        input = URL.matcher(input).replaceAll(" ");
        input = EMOJI.matcher(input).replaceAll(" ");
        input = input.replace('\u00A0', ' ');
        input = input.replace('·', '.');
        input = input.replaceAll("\\s+", " ").trim();

        return input;
    }

    public static String cleanScientificText(String input) {
        input = cleanText(input);

        if (input == null || input.isBlank()) {
            return input;
        }


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

    @Override
    public final @NotNull Iterator<Publication> iterator() {
        return this;
    }

    @Override
    public boolean hasNext() {
        return hasNextPublication;
    }

    @Override
    public final Publication next() {
        if (!hasNextPublication) {
            throw new NoSuchElementException("No more documents to parse.");
        }

        try {
            return parse();
        } finally {
            try {
                if (!hasNextPublication) {
                    in.close();
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to close the reader.", e);
            }
        }
    }

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
            throw new IllegalStateException(
                    String.format("Unable to instantiate document parser %s.", cls.getName()), e);
        }
    }

    protected abstract Publication parse();
}