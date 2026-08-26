package com.harshaandra.agentplane.api.dto;

import java.util.Map;

/** Echo of the spec a run was submitted with, embedded in {@link RunDetail}. */
public record RunSpecView(
        String agentName,
        String image,
        String prompt,
        String model,
        int maxSteps,
        int timeoutSeconds,
        Map<String, String> env,
        RunSpecRequest.ResourceSpec resources) {
}
