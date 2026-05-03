package it.unipd.dei.se.nexa.analyzer.filters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

/**
 * A Lucene TokenFilter that identifies POS patterns (like Noun-Noun or Noun-Prep-Noun)
 * and joins them into a single compound token.
 */
public final class CompoundPOSTokenFilter extends TokenFilter {

    // Lucene attributes
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final TypeAttribute typeAttr = addAttribute(TypeAttribute.class);
    private final OffsetAttribute offsetAttr = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncrAttr = addAttribute(PositionIncrementAttribute.class);

    // OpenNLP POS Tagger
    private final POSTaggerME posTagger;

    // Buffer to store tokens to be emitted
    private final LinkedList<TokenData> outputBuffer = new LinkedList<>();

    /**
     * Internal class to store token metadata during processing
     */
    private static final class TokenData {
        final String text;
        final String type;
        final int startOffset;
        final int endOffset;

        TokenData(String text, String type, int startOffset, int endOffset) {
            this.text = text;
            this.type = type;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
        }
    }

    public CompoundPOSTokenFilter(TokenStream input, POSModel posModel) {
        super(input);
        this.posTagger = new POSTaggerME(posModel);
    }

    @Override
    public boolean incrementToken() throws IOException {
        // If the buffer is empty, we need to process the next batch (the whole stream/sentence)
        if (outputBuffer.isEmpty()) {
            fillBuffer();
        }

        // If after processing the buffer is still empty, the stream is finished
        if (outputBuffer.isEmpty()) {
            return false;
        }

        // Emit the first token from the buffer
        TokenData nextToken = outputBuffer.removeFirst();

        clearAttributes();
        termAttr.append(nextToken.text);
        typeAttr.setType(nextToken.type);
        offsetAttr.setOffset(nextToken.startOffset, nextToken.endOffset);
        posIncrAttr.setPositionIncrement(1); // Standard increment

        return true;
    }

    private void fillBuffer() throws IOException {
        List<TokenData> inputTokens = new ArrayList<>();

        // Read all tokens from the input stream and store their metadata
        while (input.incrementToken()) {
            inputTokens.add(new TokenData(
                    termAttr.toString(),
                    typeAttr.type(),
                    offsetAttr.startOffset(),
                    offsetAttr.endOffset()
            ));
        }

        if (inputTokens.isEmpty()) return;

        // Extract strings for the POS Tagger
        String[] words = inputTokens.stream().map(t -> t.text).toArray(String[]::new);
        String[] tags = posTagger.tag(words);

        // Process tags to find compound patterns
        for (int i = 0; i < inputTokens.size(); ) {
            String tag1 = tags[i];

            // Check for Trigrams
            if (i + 2 < inputTokens.size()) {
                String tag2 = tags[i + 1];
                String tag3 = tags[i + 2];

                if ((tag1.startsWith("NC") && tag2.startsWith("NC") && tag3.startsWith("NC")) ||
                        (tag1.startsWith("NC") && tag2.startsWith("P") && tag3.startsWith("NC")) ||
                        (tag1.startsWith("NC") && tag2.startsWith("E") && tag3.startsWith("NC"))) {

                    outputBuffer.add(new TokenData(
                            inputTokens.get(i).text + "-" + inputTokens.get(i + 2).text,
                            "NN_COMPOUND",
                            inputTokens.get(i).startOffset,
                            inputTokens.get(i + 2).endOffset
                    ));
                    i += 3; // Skip all three processed tokens
                    continue;
                }
            }

            // Check for Bigrams
            if (i + 1 < inputTokens.size()) {
                String tag2 = tags[i + 1];

                if ((tag1.startsWith("NC") && tag2.startsWith("NC")) ||
                        (tag1.startsWith("N") && tag2.startsWith("N")) ||
                        (tag1.startsWith("V") && tag2.startsWith("NC")) ||
                        (tag1.startsWith("ADJ") && tag2.startsWith("ADJ")) ||
                        (tag1.startsWith("NC") && tag2.startsWith("ADJ"))) {

                    outputBuffer.add(new TokenData(
                            inputTokens.get(i).text + "-" + inputTokens.get(i + 1).text,
                            "NN_COMPOUND",
                            inputTokens.get(i).startOffset,
                            inputTokens.get(i + 1).endOffset
                    ));
                    i += 2; // Skip both processed tokens
                    continue;
                }
            }

            // FILTERING: Discard Articles and Prepositions
            // RD/RI = Articles, E/P = Prepositions
            if (tag1.startsWith("RD") || tag1.startsWith("RI") ||
                    tag1.startsWith("P") || tag1.startsWith("E")) {
                i++; // Discard this token and move to the next one
                continue;
            }

            // Default: Keep original token if it's not filtered or compounded
            outputBuffer.add(inputTokens.get(i));
            i++;
        }
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        outputBuffer.clear();
    }

    @Override
    public void end() throws IOException {
        super.end();
    }
}