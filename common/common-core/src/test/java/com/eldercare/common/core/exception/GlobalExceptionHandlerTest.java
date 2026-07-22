package com.eldercare.common.core.exception;

import com.eldercare.common.core.domain.R;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.TestController.class)
public class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestConfig {
    }

    @RestController
    static class TestController {

        @GetMapping("/test/biz-error")
        public void throwBizException() {
            throw new BizException(SystemErrorCode.NOT_FOUND);
        }

        @PostMapping("/test/validation")
        public String validation(@Valid @RequestBody TestRequest request) {
            return "ok";
        }

        @GetMapping("/test/unknown-error")
        public void throwUnknownException() {
            throw new RuntimeException("未知错误");
        }

        @GetMapping("/test/remote-error")
        public void throwRemoteCallException() {
            throw new RemoteCallException(new IllegalStateException("connection refused"));
        }
    }

    @Data
    static class TestRequest {
        @NotBlank(message = "name 不能为空")
        private String name;
    }

    @Test
    public void testHandleBizException() throws Exception {
        mockMvc.perform(get("/test/biz-error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(SystemErrorCode.NOT_FOUND.getCode()))
                .andExpect(jsonPath("$.msg").value(SystemErrorCode.NOT_FOUND.getMsg()));
    }

    @Test
    public void testHandleValidation() throws Exception {
        String emptyBody = "{}";
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(SystemErrorCode.BAD_REQUEST.getCode()))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].field").value("name"));
    }

    @Test
    public void testHandleUnknownException() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/unknown-error"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(SystemErrorCode.INTERNAL_ERROR.getCode()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        R<?> r = objectMapper.readValue(body, R.class);
        Assertions.assertEquals(SystemErrorCode.INTERNAL_ERROR.getCode(), r.getCode());
    }

    @Test
    public void testHandleRemoteCallException() throws Exception {
        mockMvc.perform(get("/test/remote-error"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(SystemErrorCode.REMOTE_CALL_FAILED.getCode()))
                .andExpect(jsonPath("$.msg").value(SystemErrorCode.REMOTE_CALL_FAILED.getMsg()));
    }
}
