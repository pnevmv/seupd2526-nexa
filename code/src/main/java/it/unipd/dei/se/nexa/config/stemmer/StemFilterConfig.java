package it.unipd.dei.se.nexa.config.stemmer;

import it.unipd.dei.se.nexa.config.stemmer.en.EnglishMinimalStemFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.en.EnglishPorterStemFilterConfig;
import it.unipd.dei.se.nexa.config.stemmer.en.EnglishKStemFilterConfig;
//import for other languages
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;


import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EnglishMinimalStemFilterConfig.class, name = "english_minimal"),
        @JsonSubTypes.Type(value = EnglishPorterStemFilterConfig.class, name = "english_porter"),
        @JsonSubTypes.Type(value = EnglishKStemFilterConfig.class, name = "english_kstem")
//        @JsonSubTypes.Type(value = FrenchMinimalStemFilterConfig.class, name = "french_minimal"),
//        @JsonSubTypes.Type(value = FrenchLightStemFilterConfig.class, name = "french_light"),
//        @JsonSubTypes.Type(value = FrenchSnowballStemFilterConfig.class, name = "french_snowball"),
})
public interface StemFilterConfig {
    TokenFilter toRuntime(TokenStream tokenStream);
}


