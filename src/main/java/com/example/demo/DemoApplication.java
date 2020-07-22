package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.function.*;

import java.net.URI;
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
         * {@link WriteWhenCompleteResponse} is a bad way of forcing functional MVC to deal with future dynamic responses.
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
                                    return WriteWhenCompleteResponse.adapt(oldHelloWorldService.futureFoo(Integer.parseInt(option)));
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
}