package com.example.apigateway.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.autoconfigure.web.reactive.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.reactive.error.ErrorAttributes;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;


@Component
@Order(-2) // must run before the default reactive error handler
@Slf4j
public class GlobalErrorWebExceptionHandler extends AbstractErrorWebExceptionHandler {

    public GlobalErrorWebExceptionHandler(ErrorAttributes errorAttributes,
                                           WebProperties webProperties,
                                           ApplicationContext applicationContext,
                                           ServerCodecConfigurer serverCodecConfigurer) {
        super(errorAttributes, webProperties.getResources(), applicationContext);
        this.setMessageWriters(serverCodecConfigurer.getWriters());
        this.setMessageReaders(serverCodecConfigurer.getReaders());
    }

    @Override
    protected RouterFunction<ServerResponse> getRoutingFunction(ErrorAttributes errorAttributes) {
        return RouterFunctions.route(RequestPredicates.all(), this::renderError);
    }

    private Mono<ServerResponse> renderError(org.springframework.web.reactive.function.server.ServerRequest request) {
        Throwable error = getError(request);
        HttpStatus status = mapStatus(error);

        if (status.is5xxServerError()) {
            log.error("Gateway error handling request {}", request.path(), error);
        }

        Map<String, Object> body = Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", friendlyMessage(error, status)
        );

        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);
    }

    private HttpStatus mapStatus(Throwable error) {
        if (error instanceof NotFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        if (error instanceof ConnectException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (error instanceof TimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String friendlyMessage(Throwable error, HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "No matching route for this request";
            case SERVICE_UNAVAILABLE -> "The upstream service is currently unavailable";
            case GATEWAY_TIMEOUT -> "The upstream service took too long to respond";
            default -> "An unexpected gateway error occurred";
        };
    }
}
