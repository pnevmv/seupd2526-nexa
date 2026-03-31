package it.unipd.dei.se.nexa.config.filters;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import it.unipd.dei.se.nexa.config.filters.agnostic.*;
import it.unipd.dei.se.nexa.config.filters.en.EnglishDefaultStopFilterConfig;
import it.unipd.dei.se.nexa.config.filters.en.EnglishPossessiveFilterConfig;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;

import java.io.IOException;
import java.text.ParseException;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EnglishPossessiveFilterConfig.class, name = "english_possessive"),
        @JsonSubTypes.Type(value = ASCIIFoldingFilterConfig.class, name = "ascii_folding"),
        @JsonSubTypes.Type(value = LengthFilterConfig.class, name = "length"),
        @JsonSubTypes.Type(value = EnglishDefaultStopFilterConfig.class, name = "english_stop"),
        @JsonSubTypes.Type(value = GenericStopFilterConfig.class, name = "generic_stop"),
        @JsonSubTypes.Type(value = LowerCaseFilterConfig.class, name = "lowercase"),
        @JsonSubTypes.Type(value = SpellCheckerFilterConfig.class, name = "spell_checker"),
        @JsonSubTypes.Type(value = RegexFilterConfig.class, name = "regex"),
        @JsonSubTypes.Type(value = TrimFilterConfig.class, name = "trim")
})
public interface ITokenFilterConfig {
    TokenFilter toRuntime(TokenStream tokenStream) throws IOException, ParseException;
}

