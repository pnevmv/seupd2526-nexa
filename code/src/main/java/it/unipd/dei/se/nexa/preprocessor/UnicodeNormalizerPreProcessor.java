package it.unipd.dei.se.nexa.preprocessor;

import java.text.Normalizer;

/**
 * Normalizes Unicode text to NFC form, ensuring consistent character representation.
 */
public class UnicodeNormalizerPreProcessor implements PreProcessor {

    @Override
    public String process(String text) {
        if (text == null) return null;
        return Normalizer.normalize(text, Normalizer.Form.NFC);
    }
}
