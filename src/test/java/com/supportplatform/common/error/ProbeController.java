package com.supportplatform.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Exists only to exercise {@link GlobalExceptionHandler} in tests — never
 * wired into the real application.
 */
@RestController
@Validated
public class ProbeController {

    @PostMapping("/__probe/body")
    public String body(@RequestBody @Valid Payload payload) {
        return payload.name();
    }

    @GetMapping("/__probe/param")
    public String param(@RequestParam @NotBlank String value) {
        return value;
    }

    @GetMapping("/__probe/not-found")
    public String notFound() {
        throw new ResponseStatusException(NOT_FOUND, "nothing here");
    }

    @GetMapping("/__probe/boom")
    public String boom() {
        throw new IllegalStateException("internal detail that must never reach the client");
    }

    public record Payload(@NotBlank String name) {
    }
}
