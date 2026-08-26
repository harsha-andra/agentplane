package com.harshaandra.agentplane.trace;

/** Result row of {@link TraceAnalyticsService#errorTagFrequency(int)}. */
public record TagFrequency(String tag, long count) {
}
