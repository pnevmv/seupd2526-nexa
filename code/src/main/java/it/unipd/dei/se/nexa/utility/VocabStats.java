package it.unipd.dei.se.nexa.utility;

import org.apache.lucene.index.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Dumps per-field vocabulary statistics from a Lucene index and flags likely
 * noise tokens for stop-list review.
 *
 * Output: one TSV per field written to <outDir>/<field>.tsv
 * Columns: term, df, ttf, df_pct, noise_flags
 *
 * Usage:
 *   java -cp nexa-0.1-jar-with-dependencies.jar \
 *        it.unipd.dei.se.nexa.utility.VocabStats \
 *        [indexPath=experiment/index] [outDir=vocab] [topN=0]
 *
 * topN=0 dumps all terms; topN>0 keeps only the top-N by ttf.
 */
public class VocabStats {

    // Fields to analyse (skip internal Lucene fields and stored-only fields)
    private static final List<String> TARGET_FIELDS = List.of(
            "title", "abstract",
            "title_en", "abstract_en",
            "title_fr", "abstract_fr",
            "title_de", "abstract_de",
            "venue", "authors"
    );

    // -----------------------------------------------------------------------
    // Noise detection patterns
    // -----------------------------------------------------------------------

    /** Looks like a URL or contains URL fragments */
    private static final Pattern URL = Pattern.compile(
            "https?://|www\\.|\\.(com|org|net|edu|gov|io|uk|de|fr)(/|$)|//"
    );

    /** HTML/XML tags or entities */
    private static final Pattern HTML = Pattern.compile(
            "<[^>]+>|&[a-z]{2,6};|&#\\d+;"
    );

    /** Purely numeric (optionally with punctuation) */
    private static final Pattern NUMERIC = Pattern.compile(
            "^[\\d.,\\-+%/:]+$"
    );

    /** Contains non-ASCII characters (potential encoding artefacts) */
    private static final Pattern NON_ASCII = Pattern.compile(
            "[^\\x00-\\x7F]"
    );

    /** Malformed: starts/ends with punctuation or contains repeated punctuation */
    private static final Pattern MALFORMED = Pattern.compile(
            "^[^a-zA-Z0-9]|[^a-zA-Z0-9]$|[.\\-_]{2,}"
    );

    /** Very short (1 char) — almost always noise after stemming */
    private static final int MIN_LENGTH = 2;

    /** Very long tokens — likely merged text or artefacts */
    private static final int MAX_LENGTH = 40;

    /** High document-frequency threshold: present in >80% of docs → likely function word / noise */
    private static final double HIGH_DF_PCT = 0.80;

    // -----------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        String indexPath = args.length > 0 ? args[0] : "experiment/index";
        String outDir    = args.length > 1 ? args[1] : "vocab";
        int    topN      = args.length > 2 ? Integer.parseInt(args[2]) : 0;

        Path outPath = Paths.get(outDir);
        Files.createDirectories(outPath);

        try (FSDirectory dir = FSDirectory.open(Paths.get(indexPath));
             DirectoryReader reader = DirectoryReader.open(dir)) {

            int numDocs = reader.numDocs();
            System.out.printf("Index: %s%n", indexPath);
            System.out.printf("Documents: %d%n", numDocs);
            System.out.printf("Output dir: %s%n%n", outPath.toAbsolutePath());

            for (String field : TARGET_FIELDS) {
                Terms terms = MultiTerms.getTerms(reader, field);
                if (terms == null) {
                    System.out.printf("  [skip] %-20s (no terms)%n", field);
                    continue;
                }

                long uniqueTerms = terms.size();
                long sumTtf      = terms.getSumTotalTermFreq();
                System.out.printf("  [dump] %-20s unique=%6d  sum(ttf)=%10d%n",
                        field, uniqueTerms, sumTtf);

                // Collect all terms (size() may return -1 if unknown)
                List<TermEntry> entries = new ArrayList<>(uniqueTerms > 0 ? (int) uniqueTerms : 4096);
                TermsEnum te = terms.iterator();
                BytesRef  bref;
                while ((bref = te.next()) != null) {
                    String text = bref.utf8ToString();
                    long   df   = te.docFreq();
                    long   ttf  = te.totalTermFreq();
                    entries.add(new TermEntry(text, df, ttf));
                }

                // Sort by ttf desc, then df desc
                entries.sort((a, b) -> {
                    int cmp = Long.compare(b.ttf(), a.ttf());
                    return cmp != 0 ? cmp : Long.compare(b.df(), a.df());
                });

                if (topN > 0 && entries.size() > topN)
                    entries = entries.subList(0, topN);

                // Write TSV
                Path tsv = outPath.resolve(field + ".tsv");
                try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(tsv))) {
                    pw.println("term\tdf\tttf\tdf_pct\tnoise_flags");
                    for (TermEntry e : entries) {
                        double dfPct = (double) e.df() / numDocs;
                        String flags = noiseFlags(e.term(), dfPct);
                        pw.printf("%s\t%d\t%d\t%.4f\t%s%n",
                                e.term(), e.df(), e.ttf(), dfPct, flags);
                    }
                }
            }

            System.out.println("\nDone. TSV files written to: " + outPath.toAbsolutePath());
        }
    }

    /** Returns a comma-separated list of noise labels, or empty string if clean. */
    private static String noiseFlags(String term, double dfPct) {
        List<String> flags = new ArrayList<>();

        if (term.length() < MIN_LENGTH)          flags.add("TOO_SHORT");
        if (term.length() > MAX_LENGTH)          flags.add("TOO_LONG");
        if (URL.matcher(term).find())            flags.add("URL");
        if (HTML.matcher(term).find())           flags.add("HTML");
        if (NUMERIC.matcher(term).matches())     flags.add("NUMERIC");
        if (NON_ASCII.matcher(term).find())      flags.add("NON_ASCII");
        if (MALFORMED.matcher(term).find())      flags.add("MALFORMED");
        if (dfPct >= HIGH_DF_PCT)                flags.add("HIGH_DF");

        return String.join(",", flags);
    }

    private record TermEntry(String term, long df, long ttf) {}
}
