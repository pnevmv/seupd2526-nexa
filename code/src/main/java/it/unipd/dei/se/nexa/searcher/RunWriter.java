package it.unipd.dei.se.nexa.searcher;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

public final class RunWriter implements AutoCloseable {

    private final PrintWriter writer;
    private final String runID;

    public RunWriter(final Path runFile, final String runID) throws IOException {
        this.runID = runID;
        this.writer = new PrintWriter(Files.newBufferedWriter(
                runFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
    }

    public void write(final int claimId, final String pubkey, final int rank, final float score) {
        writer.printf(Locale.ENGLISH, "%d Q0 %s %d %f %s%n",
                claimId, pubkey, rank, score, runID);
    }

    @Override
    public void close() {
        writer.close();
    }
}
