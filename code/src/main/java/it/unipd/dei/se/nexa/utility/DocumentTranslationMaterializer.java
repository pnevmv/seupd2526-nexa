package it.unipd.dei.se.nexa.utility;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.unipd.dei.se.nexa.parser.CommonParser;
import it.unipd.dei.se.nexa.parser.Publication;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DocumentTranslationMaterializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter PRETTY_WRITER = MAPPER.writerWithDefaultPrettyPrinter();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Path inputFile;
    private final Path outputFile;
    private final URI serviceUri;
    private final String targetLanguage;
    private final Set<String> languagesToTranslate;
    private final int retries;
    private final boolean overwrite;
    private final Path progressLogFile;

    private DocumentTranslationMaterializer(final Args args) {
        this.inputFile = args.inputFile;
        this.outputFile = args.outputFile;
        this.serviceUri = URI.create(args.serviceUrl);
        this.targetLanguage = args.targetLanguage;
        this.languagesToTranslate = args.languagesToTranslate;
        this.retries = args.retries;
        this.overwrite = args.overwrite;
        this.progressLogFile = args.progressLogFile;
    }

    public static void main(final String[] rawArgs) throws Exception {
        final Args args = Args.parse(rawArgs);
        new DocumentTranslationMaterializer(args).run();
    }

    private void run() throws Exception {
        if (ArgsHolder.DRY_RUN) {
            printLanguageCounts();
            return;
        }

        if (Files.exists(outputFile) && !overwrite) {
            throw new IllegalStateException("Output already exists: " + outputFile
                    + ". Pass --overwrite or use a different --output path.");
        }

        final Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int total = 0;
        int selected = 0;
        int translated = 0;
        int copied = 0;
        final long started = System.currentTimeMillis();

        try (ProgressLogger progressLogger = new ProgressLogger(progressLogFile);
             com.fasterxml.jackson.core.JsonParser parser = new JsonFactory().createParser(inputFile.toFile());
             OutputStream outputStream = Files.newOutputStream(
                     outputFile,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE);
             JsonGenerator generator = MAPPER.getFactory().createGenerator(outputStream)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("Expected a JSON array: " + inputFile);
            }

            generator.useDefaultPrettyPrinter();
            generator.writeStartArray();
            progressLogger.log("start input=%s output=%s selected_languages=%s target=%s",
                    inputFile, outputFile, String.join(",", languagesToTranslate), targetLanguage);
            System.out.printf("Starting document translation: input=%s output=%s languages=%s%n",
                    inputFile, outputFile, String.join(",", languagesToTranslate));

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                total++;
                final ObjectNode record = MAPPER.readTree(parser);
                final String detectedLanguage = detectLanguage(record);
                ensureOriginalFields(record);
                removeTranslationMetadata(record);

                if (shouldTranslate(detectedLanguage)) {
                    selected++;
                    translateRecord(record, detectedLanguage);
                    translated++;
                    logAndPrintProgress(progressLogger, "translated", total, record, detectedLanguage,
                            selected, translated, copied, started);
                } else {
                    copied++;
                    logAndPrintProgress(progressLogger, "copied", total, record, detectedLanguage,
                            selected, translated, copied, started);
                }

                PRETTY_WRITER.writeValue(generator, record);
                generator.flush();
                outputStream.flush();
            }

            generator.writeEndArray();
            generator.flush();
            progressLogger.log("finished total=%d selected=%d translated=%d copied=%d elapsed=%.1fs",
                    total, selected, translated, copied, elapsedSeconds(started));
        }

        System.out.printf(Locale.ENGLISH,
                "Translated documents written to %s%nselected=%d translated=%d copied=%d total=%d elapsed=%.1fs%n",
                outputFile, selected, translated, copied, total, elapsedSeconds(started));
    }

    private void printLanguageCounts() throws IOException {
        final Map<String, Integer> counts = new java.util.TreeMap<>();
        int selected = 0;
        int total = 0;

        try (com.fasterxml.jackson.core.JsonParser parser = new JsonFactory().createParser(inputFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalArgumentException("Expected a JSON array: " + inputFile);
            }

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                total++;
                final ObjectNode record = MAPPER.readTree(parser);
                final String detectedLanguage = detectLanguage(record);
                counts.merge(detectedLanguage, 1, Integer::sum);
                if (shouldTranslate(detectedLanguage)) {
                    selected++;
                }
            }
        }

        System.out.println("Language counts:");
        counts.forEach((language, count) -> System.out.printf("  %s: %d%n", language, count));
        System.out.printf("Selected for translation (%s -> %s): %d/%d%n",
                String.join(",", languagesToTranslate), targetLanguage, selected, total);
    }

    private boolean shouldTranslate(final String detectedLanguage) {
        return languagesToTranslate.contains(detectedLanguage)
                && !targetLanguage.equalsIgnoreCase(detectedLanguage);
    }

    private void translateRecord(final ObjectNode record, final String sourceLanguage) throws IOException {
        final String originalTitle = textValue(record, "original_title");
        final String originalAbstract = textValue(record, "original_abstract");

        if (hasTranslatableText(originalTitle)) {
            final String translatedTitle = CommonParser.cleanText(
                    translateText(originalTitle, sourceLanguage, targetLanguage));
            record.put("translated_title", translatedTitle);
            record.put(Publication.FIELD_TITLE, translatedTitle);
        }

        if (hasTranslatableText(originalAbstract) && !"#".equals(originalAbstract)) {
            final String translatedAbstract = CommonParser.cleanScientificText(
                    translateText(originalAbstract, sourceLanguage, targetLanguage));
            record.put("translated_abstract", translatedAbstract);
            record.put(Publication.FIELD_ABSTRACT, translatedAbstract);
        }
    }

    private void logAndPrintProgress(final ProgressLogger progressLogger,
                                     final String action,
                                     final int index,
                                     final ObjectNode record,
                                     final String detectedLanguage,
                                     final int selected,
                                     final int translated,
                                     final int copied,
                                     final long started) throws IOException {
        final String pubkey = record.path(Publication.FIELD_PUBKEY).asText("?");
        final double elapsed = elapsedSeconds(started);
        final String message = String.format(Locale.ENGLISH,
                "%s index=%d pubkey=%s lang=%s selected=%d translated=%d copied=%d elapsed=%.1fs",
                action, index, pubkey, detectedLanguage, selected, translated, copied, elapsed);
        progressLogger.log("%s", message);
        System.out.println(message);
    }

    private static String detectLanguage(final ObjectNode record) {
        final Publication publication = new Publication(
                record.path(Publication.FIELD_PUBKEY).asInt(),
                textValue(record, Publication.FIELD_TITLE),
                textValue(record, Publication.FIELD_ABSTRACT),
                textValue(record, Publication.FIELD_VENUE),
                textValue(record, Publication.FIELD_AUTHORS)
        );
        return LanguageDetectionUtil.detectPublicationLanguage(publication);
    }

    private static String textValue(final ObjectNode record, final String fieldName) {
        final JsonNode value = record.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static void ensureOriginalFields(final ObjectNode record) {
        if (!record.has("original_title")) {
            record.put("original_title", textValue(record, Publication.FIELD_TITLE));
        }
        if (!record.has("original_abstract")) {
            record.put("original_abstract", textValue(record, Publication.FIELD_ABSTRACT));
        }
    }

    private static void removeTranslationMetadata(final ObjectNode record) {
        record.remove("translation_provider");
        record.remove("source_language");
        record.remove("target_language");
        record.remove("translation_request_source_language");
        record.remove("detected_language");
    }

    private String translateText(final String text,
                                 final String sourceLanguage,
                                 final String targetLanguage) throws IOException {
        final String requestBody = MAPPER.writeValueAsString(Map.of(
                "source_lang_code", sourceLanguage,
                "target_lang_code", targetLanguage,
                "text", text
        ));

        IOException lastError = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                final HttpRequest request = HttpRequest.newBuilder(serviceUri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                        .build();

                final HttpResponse<String> response = httpClient.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("TranslateGemma request failed with status "
                            + response.statusCode() + ": " + response.body());
                }

                final JsonNode root = MAPPER.readTree(response.body());
                final String translation = root.path("translation").asText("").trim();
                if (translation.isBlank()) {
                    throw new IOException("TranslateGemma response did not contain a non-empty translation: "
                            + response.body());
                }
                return translation;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("TranslateGemma request interrupted.", e);
            } catch (IOException e) {
                lastError = e;
                if (attempt >= retries) {
                    break;
                }
                try {
                    Thread.sleep((long) Math.pow(2, attempt) * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while retrying TranslateGemma request.", interrupted);
                }
            }
        }

        throw new IOException("Translation failed after " + (retries + 1) + " attempt(s).", lastError);
    }

    private static boolean hasTranslatableText(final String value) {
        return value != null && !value.isBlank();
    }

    private static double elapsedSeconds(final long startedMillis) {
        return (System.currentTimeMillis() - startedMillis) / 1000.0;
    }

    private static final class Args {
        private Path inputFile = Path.of("datasets/collection_data.json");
        private Path outputFile = Path.of("datasets/processed/documents/translated/collection_data_en_translated.json");
        private String serviceUrl = "http://127.0.0.1:8008/translate";
        private String targetLanguage = LanguageDetectionUtil.ENGLISH;
        private Set<String> languagesToTranslate = new LinkedHashSet<>(Arrays.asList(
                LanguageDetectionUtil.FRENCH,
                LanguageDetectionUtil.GERMAN
        ));
        private int retries = 2;
        private boolean overwrite = false;
        private Path progressLogFile = Path.of("logs/document-translation.log");

        private static Args parse(final String[] rawArgs) {
            final Args args = new Args();
            for (int i = 0; i < rawArgs.length; i++) {
                final String key = rawArgs[i];
                final String value = i + 1 < rawArgs.length ? rawArgs[i + 1] : null;
                switch (key) {
                    case "--input" -> args.inputFile = Path.of(requireValue(key, value));
                    case "--output" -> args.outputFile = Path.of(requireValue(key, value));
                    case "--service-url" -> args.serviceUrl = requireValue(key, value);
                    case "--target-lang" -> args.targetLanguage = requireValue(key, value).toLowerCase(Locale.ROOT);
                    case "--languages" -> args.languagesToTranslate = parseLanguages(requireValue(key, value));
                    case "--retries" -> args.retries = Integer.parseInt(requireValue(key, value));
                    case "--overwrite" -> {
                        args.overwrite = true;
                        i--;
                    }
                    case "--progress-log" -> args.progressLogFile = Path.of(requireValue(key, value));
                    case "--dry-run" -> {
                        ArgsHolder.DRY_RUN = true;
                        i--;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + key);
                }
                i++;
            }
            return args;
        }

        private static String requireValue(final String key, final String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing value for " + key);
            }
            return value;
        }

        private static Set<String> parseLanguages(final String value) {
            final Set<String> languages = new LinkedHashSet<>();
            for (String language : value.split(",")) {
                if (!language.isBlank()) {
                    languages.add(language.trim().toLowerCase(Locale.ROOT));
                }
            }
            return languages;
        }
    }

    private static final class ArgsHolder {
        private static boolean DRY_RUN = false;
    }

    private static final class ProgressLogger implements AutoCloseable {
        private final java.io.BufferedWriter writer;

        private ProgressLogger(final Path path) throws IOException {
            final Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.writer = Files.newBufferedWriter(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE);
        }

        private synchronized void log(final String format, final Object... args) throws IOException {
            final String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            writer.write(timestamp + " " + String.format(Locale.ENGLISH, format, args));
            writer.newLine();
            writer.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            writer.close();
        }
    }
}
