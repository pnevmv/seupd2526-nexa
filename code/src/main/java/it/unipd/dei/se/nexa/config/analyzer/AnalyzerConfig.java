package it.unipd.dei.se.nexa.config.analyzer;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.lucene.analysis.Analyzer;

/**
 * Checking the approach
 * Interface for analyzer configuration objects that define how Lucene analyzers are constructed.
 *
 * <p>This interface is used to support polymorphic deserialization from configuration files using Jackson.
 * Implementing classes (e.g., {@link GenericAnalyzerConfig}, {@link FrenchAnalyzerConfig}, {@link GermanAnalyzerConfig}) specify
 * how to construct concrete {@link Analyzer} instances at runtime.</p>
 *
 * <p>Jackson uses the {@code type} property in the JSON to determine which subtype to instantiate.</p>
 *
 * <pre>{@code
 * {
 *   "type": "generic",
 *   "stopwords": ["a", "the", "of"]
 * }
 * }</pre>
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = EnglishAnalyzerConfig.class, name = "english"),
        @JsonSubTypes.Type(value = GermanAnalyzerConfig.class, name = "german")
//        @JsonSubTypes.Type(value = FrenchAnalyzerConfig.class, name = "french")
})
public interface AnalyzerConfig {
    Analyzer toRuntime();
}

*/