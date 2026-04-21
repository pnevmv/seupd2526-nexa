package it.unipd.dei.se.nexa.utility;

import java.io.IOException;

public interface TranslationService {

    String translate(String text, String sourceLanguage, String targetLanguage) throws IOException;
}
