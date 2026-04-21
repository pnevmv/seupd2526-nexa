package it.unipd.dei.se.nexa.utility;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class CommandLineTranslationService implements TranslationService {

    private final String command;

    public CommandLineTranslationService(final String command) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("translationCommand must be configured when translationProvider=command.");
        }

        this.command = command;
    }

    @Override
    public String translate(final String text,
                            final String sourceLanguage,
                            final String targetLanguage) throws IOException {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (sourceLanguage == null || sourceLanguage.isBlank() || targetLanguage == null || targetLanguage.isBlank()) {
            throw new IllegalArgumentException("Source and target languages must be provided for translation.");
        }
        if (sourceLanguage.equalsIgnoreCase(targetLanguage)) {
            return text;
        }

        final ProcessBuilder processBuilder = new ProcessBuilder(command, sourceLanguage, targetLanguage);
        processBuilder.redirectErrorStream(false);

        final Process process = processBuilder.start();
        try (var writer = new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(text);
        }

        try {
            final String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            final String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            final int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new IOException("Translation command failed with exit code " + exitCode
                        + (stderr.isBlank() ? "." : ": " + stderr));
            }
            if (stdout.isBlank()) {
                throw new IOException("Translation command returned empty output.");
            }

            return stdout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Translation command was interrupted.", e);
        }
    }
}
