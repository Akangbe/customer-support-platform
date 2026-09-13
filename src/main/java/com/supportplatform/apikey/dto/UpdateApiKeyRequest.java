package com.supportplatform.apikey.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * A partial update: every field is optional and an omitted one is left
 * alone. Deliberately cannot reach {@code keyId}, the secret, the tenant or
 * the active flag — the first three are identity rather than settings, and
 * the last has its own activate/deactivate verbs precisely so an operator
 * has to say which direction they meant.
 *
 * @param contactEmail an address to set, or {@code ""} to remove the one on
 *                     file. Removing has to be expressible: a partner whose
 *                     ops contact leaves should not keep receiving a
 *                     tenant's volume alerts, and "send me null" is not
 *                     something a JSON body can say distinctly from "I did
 *                     not mention this field".
 */
public record UpdateApiKeyRequest(
        @Size(max = 200) String name,
        @Size(max = 320) String contactEmail,
        @Min(1) @Max(10_000) Integer rateLimit
) {
}
