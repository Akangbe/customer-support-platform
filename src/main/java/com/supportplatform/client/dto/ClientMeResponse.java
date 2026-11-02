package com.supportplatform.client.dto;

/** Deliberately has no tenant or account id: a client has no use for either and should not learn them. */
public record ClientMeResponse(String email, String name, String accountName) {
}
