package com.example.demo;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    static class FeatureManager {
        static boolean isActive() {
            return ThreadLocalRandom.current().nextBoolean();
        }
    }

    @Configuration
    static class ServerRouteConfiguration {

        /**
         * as you can see here, using functional MVC, one must provide a {@link org.springframework.web.servlet.function.HandlerFunction}
         * and a HandlerFunction, only allows to return {@link ServerResponse}. and all packaged implementations of ServerResponse
         * come with a final StatusCode.
         * <p>
         * we have {@link org.springframework.web.servlet.function.EntityResponse} that allows for {@link java.util.concurrent.CompletionStage}
         * as its entity.. but the CompletionStage has no ability to affect the wrapping EntityResponse's StatusCode, or for that matter,
         * no ability to create a "Location" header as well.
         * <p>
         * {@link FutureServerResponse} is a bad way of forcing functional MVC to deal with future dynamic responses.
         * <p>
         * is there a real way?
         */
        @Bean
        RouterFunction<ServerResponse> helloWorldRouterFunction(OldHelloWorldService oldHelloWorldService) {
            return RouterFunctions.route()
                    .route(RequestPredicates.path("/helloWorld/{option}"), x ->
                            {
                                String option = x.pathVariable("option");
                                if (FeatureManager.isActive()) {
                                    return ServerResponse.ok().body(String.format("New implementation of Hello World! your option is: %s", option));
                                } else {
                                    return FutureServerResponse.from(oldHelloWorldService.futureFoo(Integer.parseInt(option)));
                                }
                            }
                    )
                    .build();
        }
    }

    @RestController
    static class OldHelloWorldService {

        @RequestMapping("/specialCase")
        ResponseEntity<String> specialCase() {
            // some business logic
            return ResponseEntity.ok().body("Special case for Hello World with option 2");
        }

        /**
         * Old declarative implementation, routed via functional {@link ServerRouteConfiguration}
         * to allow dynamic choice based on {@link FeatureManager#isActive()}
         * <p>
         * as you can see, before the change, this function was a {@link RequestMapping} and it handled the
         * completable future, we could return both concrete OK responses with a body, and FOUND responses with a location.
         */
        // @RequestMapping("/helloWorld/{option}")
        CompletableFuture<ResponseEntity<String>> futureFoo(
                // @PathVariable
                int option) {
            return CompletableFuture.supplyAsync(() -> {
                if (option == 2) {
                    return ResponseEntity.status(HttpStatus.FOUND)
                            .location(URI.create("/specialCase"))
                            .build();
                } else {
                    return ResponseEntity.ok().body(String.format("Old implementation of Hello World! your option is: %s", option));
                }
            });
        }
    }

    /**
     * this solution works, but is not very optimal.. for instance it ignores the reasoning for making all the fields of
     * {@link org.springframework.web.servlet.function.EntityResponse} implementation final! it also handles errors in a ... less than optimal ... way.
     */
    @RequiredArgsConstructor
    static class FutureServerResponse<T> implements ServerResponse {

        private final CompletableFuture<ResponseEntity<T>> cs;

        @SneakyThrows
        Optional<ResponseEntity<T>> csr() {
            if (cs.isDone()) {
                return Optional.ofNullable(cs.get());
            }
            return Optional.empty();
        }

        public static <T> FutureServerResponse<T> from(CompletableFuture<ResponseEntity<T>> cs) {
            return new FutureServerResponse<>(cs);
        }

        @NonNull
        @Override
        public HttpStatus statusCode() {
            return csr().map(ResponseEntity::getStatusCode).orElseThrow(() -> new RuntimeException("not ready"));
        }

        @Override
        public int rawStatusCode() {
            return statusCode().value();
        }

        @SneakyThrows
        @NonNull
        @Override
        public HttpHeaders headers() {
            return csr().map(ResponseEntity::getHeaders).orElseThrow(() -> new RuntimeException("not ready"));
        }

        @NonNull
        @Override
        public MultiValueMap<String, Cookie> cookies() {
            return new LinkedMultiValueMap<>();
        }

        @Override
        public ModelAndView writeTo(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull Context context) throws ServletException, IOException {
            AsyncContext asyncContext = request.startAsync(request, response);

            cs.whenComplete((responseEntity, throwable) ->
                    {
                        try {
                            if (responseEntity != null) {
                                BodyBuilder bodyBuilder = ServerResponse.status(responseEntity.getStatusCode())
                                        .headers(x -> x.addAll(responseEntity.getHeaders()));
                                if (responseEntity.getBody() == null) {
                                    bodyBuilder.build()
                                            .writeTo(request, response, context);
                                } else {
                                    bodyBuilder.body(responseEntity.getBody())
                                            .writeTo(request, response, context);
                                }
                            }
                            if (throwable != null) {
                                ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(throwable.getMessage())
                                        .writeTo(request, response, context);
                            }
                        } catch (Throwable t) {
                            try {
                                ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body(t.getMessage())
                                        .writeTo(request, response, context);
                            } catch (Throwable ignored) {
                            }
                        } finally {
                            asyncContext.complete();
                        }
                    }
            );
            return null;
        }
    }

}