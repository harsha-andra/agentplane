package com.harshaandra.agentplane.orchestration;

/** Thrown when creating a tenant whose slug already exists. Mapped to 409. */
public class DuplicateSlugException extends RuntimeException {

    public DuplicateSlugException(String slug) {
        super("Tenant slug already in use: " + slug);
    }
}
