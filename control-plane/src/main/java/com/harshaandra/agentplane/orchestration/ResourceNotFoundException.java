package com.harshaandra.agentplane.orchestration;

/** Thrown when a run/tenant/etc looked up by id does not exist. Mapped to 404 by the API layer. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
