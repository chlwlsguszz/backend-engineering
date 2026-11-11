package com.marketengine.backend.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketengine.backend.common.exception.ErrorCode;
import com.marketengine.backend.common.web.support.ValidatedBody;

@WebMvcTest(controllers = ExceptionTestController.class)
@Import(GlobalExceptionHandler.class)
class GlobalExceptionHandlerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void businessException_returnsApiErrorEnvelope() throws Exception {
        mockMvc.perform(get("/__exception-test/business"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()))
                .andExpect(jsonPath("$.error.message").value("not found"));
    }

    @Test
    void validationError_returnsFieldErrors() throws Exception {
        String json = objectMapper.writeValueAsString(new ValidatedBody(""));
        mockMvc.perform(post("/__exception-test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.error.details.fieldErrors.name").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {"lock-timeout", "statement-timeout", "uncategorized/55P03", "uncategorized/57014"})
    void databaseDeadlinesReturnRetryableServiceUnavailable(String endpoint) throws Exception {
        mockMvc.perform(get("/__exception-test/" + endpoint))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.ORDER_BUSY.name()))
                .andExpect(jsonPath("$.error.details.retryable").value(true));
    }

    @Test
    void unrelatedUnclassifiedSqlFailureIsNotAdvertisedAsRetryable() throws Exception {
        mockMvc.perform(get("/__exception-test/uncategorized/XX000"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.error.code").value(ErrorCode.INTERNAL_ERROR.name()));
    }
}
