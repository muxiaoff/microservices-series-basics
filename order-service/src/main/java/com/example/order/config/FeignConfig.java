package com.example.order.config;

import com.example.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import feign.Response;
import feign.codec.DecodeException;
import feign.codec.Decoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/**
 * Custom Feign configuration to unwrap ApiResponse<T> from downstream services.
 *
 * The microservices wrap responses with {@link ApiResponse}. This decoder transparently
 * extracts the {@code data} field so Feign clients can declare plain return types.
 */
@Configuration
public class FeignConfig {

    @Bean
    public Decoder apiResponseDecoder() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        return (Response response, Type type) -> {
            if (response.body() == null) {
                return null;
            }
            try (InputStream is = response.body().asInputStream()) {
                // Build ApiResponse<T> type
                JavaType apiResponseType = mapper.getTypeFactory()
                    .constructParametricType(ApiResponse.class, mapper.getTypeFactory().constructType(type));

                ApiResponse<?> apiResponse = mapper.readValue(is, apiResponseType);

                if (apiResponse == null) {
                    return null;
                }
                // If the response code indicates failure, propagate as exception
                if (apiResponse.getCode() != 200) {
                    throw new DecodeException(response.status(),
                        "Remote service returned error: code=" + apiResponse.getCode()
                            + ", message=" + apiResponse.getMessage(),
                        response.request());
                }
                return apiResponse.getData();
            } catch (DecodeException e) {
                throw e;
            } catch (IOException e) {
                throw new DecodeException(response.status(), "Failed to decode ApiResponse: " + e.getMessage(), response.request(), e);
            }
        };
    }
}
