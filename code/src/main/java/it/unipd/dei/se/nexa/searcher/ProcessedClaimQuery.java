package it.unipd.dei.se.nexa.searcher;

import it.unipd.dei.se.nexa.parser.Claim;

public record ProcessedClaimQuery(Claim claim, String text, boolean translated) {
}
