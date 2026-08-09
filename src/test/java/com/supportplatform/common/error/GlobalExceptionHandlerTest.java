package com.supportplatform.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the error-handling foundation translates common failure modes
 * into the stable {@link ErrorResponse} shape, using {@link ProbeController}
 * — a throwaway controller that exists only for this test.
 */
@WebMvcTest(controllers = ProbeController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void beanValidationFailureReturns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/__probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void constraintViolationOnRequestParamReturns400() throws Exception {
        mockMvc.perform(get("/__probe/param").param("value", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void responseStatusExceptionIsPassedThroughWithReason() throws Exception {
        mockMvc.perform(get("/__probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("nothing here"));
    }

    @Test
    void unexpectedExceptionReturns500WithoutLeakingDetail() throws Exception {
        mockMvc.perform(get("/__probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
    }
}
