package de.mhus.hrafnagud.api.filter;

/**
 * Both pipelines' conclusions, so an article is built from one value.
 *
 * <p>{@link #unfiltered()} is what the callers that do not filter pass — and
 * what makes adding this to the ingest path additive rather than a rewrite:
 * "no rules were applied" and "the rules accepted it" are the same article,
 * because the default is accept.
 */
public record FilterOutcomes(FilterOutcome content, FilterOutcome translation) {

    private static final FilterOutcomes UNFILTERED =
            new FilterOutcomes(FilterOutcome.defaultAccept(), FilterOutcome.defaultAccept());

    public static FilterOutcomes unfiltered() {
        return UNFILTERED;
    }

    public FilterOutcome forPipeline(FilterPipeline pipeline) {
        return pipeline == FilterPipeline.CONTENT ? content : translation;
    }
}
