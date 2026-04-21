package it.unipd.dei.se.nexa.utility;

import java.io.IOException;

public final class NoOpTranslationService implements TranslationService {

    @Override
    public String translate(final String text,
                            final String sourceLanguage,
                            final String targetLanguage) throws IOException {
        return text;
    }
}
