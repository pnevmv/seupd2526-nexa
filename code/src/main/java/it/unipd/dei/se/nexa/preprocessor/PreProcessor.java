package it.unipd.dei.se.nexa.preprocessor;

/**
 * Contract for text pre-processing steps applied before analysis.
 */
public interface PreProcessor {

    /**
     * Processes the input string and returns the cleaned result.
     *
     * @param text the raw input text
     * @return the processed text
     */
    String process(String text);
}
