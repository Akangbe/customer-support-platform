package com.supportplatform.user;

public enum UserRole {
    OWNER,
    ADMIN,
    MANAGER,
    AGENT,
    /** A person at a client business (V21): reads that client's data, and is not on the tenant's team. */
    CLIENT
}
