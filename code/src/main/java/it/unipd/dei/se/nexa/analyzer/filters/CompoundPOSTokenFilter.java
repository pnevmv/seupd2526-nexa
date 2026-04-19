package it.unipd.dei.se.nexa.analyzer.filters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;

import opennlp.tools.postag.POSModel;
import opennlp.tools.postag.POSTaggerME;

public final class CompoundPOSTokenFilter extends TokenFilter {
    private final CharTermAttribute termAttr = addAttribute(CharTermAttribute.class);
    private final TypeAttribute typeAttr = addAttribute(TypeAttribute.class);
    private final LinkedList<State> tokenBuffer = new LinkedList<>();
    private final POSTaggerME posTagger;

    public CompoundPOSTokenFilter(TokenStream input, POSModel posModel) {
        super(input);
        this.posTagger = new POSTaggerME(posModel);
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (tokenBuffer.isEmpty()) {
            List<String> tokens = new ArrayList<>();
            List<String> posTags;

            // Leggi e memorizza tutti i token dallo stream
            while (input.incrementToken()) {
                tokenBuffer.add(captureState());
            }

            if (tokenBuffer.isEmpty()) {
                return false;
            }

            // Ottieni i token come stringhe
            for (State state : tokenBuffer) {
                restoreState(state);
                tokens.add(termAttr.toString());
            }

            posTags = Arrays.asList(posTagger.tag(tokens.toArray(new String[0])));

            // Prepara una nuova lista di stati (tokens combinati)
            LinkedList<State> newBuffer = new LinkedList<>();

            for (int i = 0; i < tokens.size(); ) {
                String tag1 = posTags.get(i);

                if (i + 2 < tokens.size()) {
                    String tag2 = posTags.get(i + 1);
                    String tag3 = posTags.get(i + 2);

                    if ((tag1.startsWith("NC") && tag2.startsWith("NC") && tag3.startsWith("NC")) ||
                            (tag1.startsWith("NC") && tag2.startsWith("P") && tag3.startsWith("NC"))) {

                        clearAttributes();
                        termAttr.append(tokens.get(i)).append("-").append(tokens.get(i + 2));
                        typeAttr.setType("NN");
                        newBuffer.add(captureState());
                        i += 3;
                        continue;
                    }
                }

                if (i + 1 < tokens.size()) {
                    String tag2 = posTags.get(i + 1);

                    if ((tag1.startsWith("NC") && tag2.startsWith("NC")) ||
                            (tag1.startsWith("N") && tag2.startsWith("N")) ||
                            (tag1.startsWith("V") && tag2.startsWith("NC")) ||
                            (tag1.startsWith("ADJ") && tag2.startsWith("ADJ")) ||
                            (tag1.startsWith("NC") && tag2.startsWith("ADJ"))) {

                        clearAttributes();
                        termAttr.append(tokens.get(i)).append("-").append(tokens.get(i + 1));
                        typeAttr.setType("NN");
                        newBuffer.add(captureState());
                        i += 2;
                        continue;
                    }
                }

                clearAttributes();
                termAttr.append(tokens.get(i));
                typeAttr.setType(tag1);
                newBuffer.add(captureState());
                i++;
            }
            tokenBuffer.clear();
            tokenBuffer.addAll(newBuffer);

        }

        if (!tokenBuffer.isEmpty()) {
            restoreState(tokenBuffer.removeFirst());
            return true;
        } else {
            return false;
        }
    }
}



