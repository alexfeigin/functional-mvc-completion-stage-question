package org.springframework.web.servlet.function;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

public class WriteWhenCompleteResponse extends DefaultServerResponseBuilder.AbstractServerResponse {
    private static final HttpHeaders EMPTY_HTTP_HEADERS = new HttpHeaders();
    private static final LinkedMultiValueMap<String, Cookie> EMPTY_COOKIES = new LinkedMultiValueMap<>();
    private final CompletionStage<ServerResponse> entity;

    private WriteWhenCompleteResponse(CompletionStage<ServerResponse> entity) {
        super(200, EMPTY_HTTP_HEADERS, EMPTY_COOKIES);
        this.entity = entity;
    }

    public static WriteWhenCompleteResponse whenComplete(CompletionStage<ServerResponse> entity) {
        Objects.requireNonNull(entity);
        return new WriteWhenCompleteResponse(entity);
    }

    public static <T> WriteWhenCompleteResponse adaptComplete(CompletionStage<ResponseEntity<T>> entity) {
        Objects.requireNonNull(entity);
        return new WriteWhenCompleteResponse(entity.thenApply(WriteWhenCompleteResponse::adapt));
    }

    private static <T> ServerResponse adapt(ResponseEntity<T> tResponseEntity) {
        BodyBuilder builder = ServerResponse.status(tResponseEntity.getStatusCode())
                .headers(hb -> hb.addAll(tResponseEntity.getHeaders()));
        return Optional.ofNullable(tResponseEntity.getBody())
                .map(builder::body)
                .orElseGet(builder::build);
    }

    @Override
    public ModelAndView writeTo(HttpServletRequest servletRequest, HttpServletResponse servletResponse,
                                Context context) throws ServletException, IOException {
        AsyncContext asyncContext = servletRequest.startAsync(servletRequest, servletResponse);
        entity.whenComplete((entity, throwable) -> {
            try {
                if (entity != null) {
                    entity.writeTo(servletRequest, servletResponse, context);
                } else if (throwable != null) {
                    handleError(throwable, servletRequest, servletResponse, context);
                }
            } catch (Throwable t) {
                handleError(t, servletRequest, servletResponse, context);
            } finally {
                asyncContext.complete();
            }
        });
        return null;
    }

    @Override
    protected ModelAndView writeToInternal(HttpServletRequest request, HttpServletResponse response, Context context) throws ServletException, IOException {
        // unreachable
        return null;
    }
}
