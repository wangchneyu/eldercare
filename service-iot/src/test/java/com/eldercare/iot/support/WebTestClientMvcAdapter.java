package com.eldercare.iot.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;

/**
 * Keeps the existing CRUD assertions readable while exercising the WebFlux test client.
 */
public final class WebTestClientMvcAdapter {

    private final WebTestClient client;
    private final ObjectMapper objectMapper;

    public WebTestClientMvcAdapter(WebTestClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public ResultActions perform(RequestBuilder request) {
        String uri = UriComponentsBuilder.fromPath(request.path).queryParams(request.parameters).build().toUriString();
        WebTestClient.ResponseSpec response = switch (request.method) {
            case "GET" -> client.get().uri(uri).exchange();
            case "DELETE" -> client.delete().uri(uri).exchange();
            case "POST" -> client.post().uri(uri).contentType(request.contentType).bodyValue(request.body).exchange();
            case "PUT" -> client.put().uri(uri).contentType(request.contentType).bodyValue(request.body).exchange();
            case "PATCH" -> client.patch().uri(uri).contentType(request.contentType).bodyValue(request.body).exchange();
            default -> throw new IllegalArgumentException("Unsupported method: " + request.method);
        };
        return new ResultActions(response.expectBody().returnResult(), objectMapper);
    }

    public static RequestBuilder get(String path, Object... variables) {
        return request("GET", path, variables);
    }

    public static RequestBuilder post(String path, Object... variables) {
        return request("POST", path, variables);
    }

    public static RequestBuilder put(String path, Object... variables) {
        return request("PUT", path, variables);
    }

    public static RequestBuilder patch(String path, Object... variables) {
        return request("PATCH", path, variables);
    }

    public static RequestBuilder delete(String path, Object... variables) {
        return request("DELETE", path, variables);
    }

    public static StatusAssertions status() {
        return new StatusAssertions();
    }

    public static JsonPathAssertions jsonPath(String expression) {
        return new JsonPathAssertions(expression);
    }

    private static RequestBuilder request(String method, String path, Object... variables) {
        String expanded = path;
        for (Object variable : variables) {
            expanded = expanded.replaceFirst("\\{[^}]+}", java.util.regex.Matcher.quoteReplacement(String.valueOf(variable)));
        }
        return new RequestBuilder(method, expanded);
    }

    public static final class RequestBuilder {
        private final String method;
        private final String path;
        private final MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        private MediaType contentType = MediaType.APPLICATION_JSON;
        private String body = "";

        private RequestBuilder(String method, String path) {
            this.method = method;
            this.path = path;
        }

        public RequestBuilder contentType(MediaType contentType) {
            this.contentType = contentType;
            return this;
        }

        public RequestBuilder content(String body) {
            this.body = body;
            return this;
        }

        public RequestBuilder param(String name, String value) {
            parameters.add(name, value);
            return this;
        }
    }

    public static final class ResultActions {
        private final EntityExchangeResult<byte[]> result;
        private final ObjectMapper objectMapper;

        private ResultActions(EntityExchangeResult<byte[]> result, ObjectMapper objectMapper) {
            this.result = result;
            this.objectMapper = objectMapper;
        }

        public ResultActions andExpect(Expectation expectation) throws Exception {
            expectation.verify(result, objectMapper);
            return this;
        }

        public MvcResult andReturn() {
            return new MvcResult(result);
        }
    }

    @FunctionalInterface
    public interface Expectation {
        void verify(EntityExchangeResult<byte[]> result, ObjectMapper objectMapper) throws Exception;
    }

    public static final class StatusAssertions {
        public Expectation isOk() {
            return hasStatus(200);
        }

        public Expectation isCreated() {
            return hasStatus(201);
        }

        public Expectation isConflict() {
            return hasStatus(409);
        }

        public Expectation isNotFound() {
            return hasStatus(404);
        }

        private Expectation hasStatus(int expected) {
            return (result, mapper) -> {
                HttpStatusCode actual = result.getStatus();
                if (actual.value() != expected) {
                    throw new AssertionError("Expected HTTP " + expected + " but was " + actual.value());
                }
            };
        }
    }

    public static final class JsonPathAssertions {
        private final String expression;

        private JsonPathAssertions(String expression) {
            this.expression = expression;
        }

        public Expectation value(Object expected) {
            return (result, mapper) -> {
                JsonNode node = node(result, mapper);
                String actual = node.isValueNode() ? node.asText() : node.toString();
                if (!String.valueOf(expected).equals(actual)) {
                    throw new AssertionError("Expected " + expression + "=" + expected + " but was " + actual);
                }
            };
        }

        public Expectation exists() {
            return (result, mapper) -> {
                if (node(result, mapper).isMissingNode()) {
                    throw new AssertionError("Expected JSON path to exist: " + expression);
                }
            };
        }

        public Expectation isArray() {
            return (result, mapper) -> {
                if (!node(result, mapper).isArray()) {
                    throw new AssertionError("Expected JSON array at: " + expression);
                }
            };
        }

        private JsonNode node(EntityExchangeResult<byte[]> result, ObjectMapper mapper) throws Exception {
            JsonNode current = mapper.readTree(result.getResponseBody());
            for (String field : expression.replaceFirst("^\\$\\.", "").split("\\.")) {
                current = current.path(field);
            }
            return current;
        }
    }

    public static final class MvcResult {
        private final EntityExchangeResult<byte[]> result;

        private MvcResult(EntityExchangeResult<byte[]> result) {
            this.result = result;
        }

        public Response getResponse() {
            return new Response(result.getResponseBody());
        }
    }

    public static final class Response {
        private final byte[] body;

        private Response(byte[] body) {
            this.body = body;
        }

        public String getContentAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
