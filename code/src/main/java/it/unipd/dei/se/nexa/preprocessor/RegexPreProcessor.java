package it.unipd.dei.se.nexa.preprocessor;

import java.util.regex.Pattern;

/**
 * Applies a regex substitution to clean text.
 */
public class RegexPreProcessor implements PreProcessor {

    private final Pattern pattern;
    private final String replacement;

    public RegexPreProcessor(Pattern pattern, String replacement) {
        this.pattern = pattern;
        this.replacement = replacement;
    }

    @Override
    public String process(String text) {
        if (text == null) return null;
        return pattern.matcher(text).replaceAll(replacement);
    }

    /**
     * Strips Twitter-style mentions (e.g. {@code @username}) from text.
     */
    public static RegexPreProcessor twitterMentionStripper() {
        return new RegexPreProcessor(Pattern.compile("@\\w+"), " ");
    }
}
