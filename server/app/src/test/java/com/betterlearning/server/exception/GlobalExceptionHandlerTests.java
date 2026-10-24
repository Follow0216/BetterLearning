package com.betterlearning.server.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.model.dto.RegisterUserDTO;
import com.betterlearning.server.security.TraceIdContext;
import com.betterlearning.server.security.TraceIdFilter;
import com.jayway.jsonpath.JsonPath;

import jakarta.validation.Valid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class GlobalExceptionHandlerTests {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        JsonMapper jsonMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(new ApiErrorResponseFactory()))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                .setValidator(validator)
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void rejectsUnknownJsonProperty() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"password1\","
                                + "\"confirmPassword\":\"password1\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0427"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(header().exists(TraceIdContext.HEADER))
                .andExpect(content().string(not(containsString("ADMIN"))));
    }

    @Test
    void returnsFieldErrorsForInvalidRequest() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\",\"password\":\"password1\","
                                + "\"confirmPassword\":\"password1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0400"))
                .andExpect(jsonPath("$.fieldErrors.username").exists())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void returnsConfirmPasswordFieldErrorForMismatch() throws Exception {
        mockMvc.perform(post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"alice\",\"password\":\"password1\","
                + "\"confirmPassword\":\"password2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.confirmPassword")
                .value("两次输入的密码不一致"));
    }

    @Test
    void usesBusinessErrorCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("A0501"))
                .andExpect(jsonPath("$.message").value("请求过于频繁"))
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void returnsJsonBusinessErrorForPngOnlyAcceptOnImageRoute() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/image-not-found").accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("A0606"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.traceId"))
                .isEqualTo(result.getResponse().getHeader(TraceIdContext.HEADER));
    }

    @Test
    void returns406JsonWhenAcceptDoesNotMatchImageRoute() throws Exception {
        mockMvc.perform(get("/test/image-not-found").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("A0429"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void returnsServiceUnavailableForConnectionFailure() throws Exception {
        mockMvc.perform(get("/test/unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("C0001"))
                .andExpect(content().string(not(containsString("jdbc:postgresql"))))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void returnsInternalErrorForUnhandledException() throws Exception {
        mockMvc.perform(get("/test/unknown"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("B0001"))
                .andExpect(content().string(not(containsString("secret-token"))));
    }

    @Test
    void doesNotMapNonPostgresTextToConflict() throws Exception {
        mockMvc.perform(get("/test/phone-conflict"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("B0001"))
                .andExpect(content().string(not(containsString("+8613800138000"))))
                .andExpect(content().string(not(containsString("uk_app_user_phone"))));
    }

    @Test
    void returnsInternalErrorForUnknownIntegrityViolation() throws Exception {
        mockMvc.perform(get("/test/other-integrity"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("B0001"));
    }

    @Test
    void generatesServerTraceIdForErrorResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/business")
                .header(TraceIdContext.HEADER, "trace-abc"))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        String headerTraceId = result.getResponse().getHeader(TraceIdContext.HEADER);
        String bodyTraceId = JsonPath.read(
                result.getResponse().getContentAsString(), "$.traceId");
        assertThat(headerTraceId).hasSize(32).isNotEqualTo("trace-abc");
        assertThat(bodyTraceId).isEqualTo(headerTraceId);
    }

    @RestController
    static class TestController {

        @PostMapping("/test/validation")
        public void validate(@Valid @RequestBody RegisterUserDTO request) {
        }

        @GetMapping("/test/business")
        public void business() {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        @GetMapping(value = "/test/image-not-found", produces = MediaType.IMAGE_PNG_VALUE)
        public void imageNotFound() {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }

        @GetMapping("/test/unavailable")
        public void unavailable() {
            throw new CannotGetJdbcConnectionException(
                    "jdbc:postgresql://secret-host/db?password=secret");
        }

        @GetMapping("/test/unknown")
        public void unknown() {
            throw new IllegalStateException("secret-token");
        }

        @GetMapping("/test/phone-conflict")
        public void phoneConflict() {
            throw new DuplicateKeyException(
                    "Key (phone)=(+8613800138000) already exists. "
                            + "Constraint: uk_app_user_phone");
        }

        @GetMapping("/test/other-integrity")
        public void otherIntegrity() {
            throw new DataIntegrityViolationException("null value in column username");
        }
    }
}
