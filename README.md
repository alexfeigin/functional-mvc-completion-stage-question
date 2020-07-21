# functional-mvc-completion-stage-question


companion repo for [stackoverflow question](https://stackoverflow.com/questions/63019544/upgrading-a-springboot-web-mvc-from-declarative-to-functional?noredirect=1#comment111442460_63019544)

I have a working production SpringBoot application, and part of it is getting a do-over. It would be very beneficial for me to delete my old `@RequestMapping` from the `ResponseEntity<String> foo()`s of my world, keeping the old code as an as a duplicate while we try to roll out the new functionality behind a feature gate.. All production tenants go through my no-longer-declarative `foo()` function, while all my test and automation tenants can start to tinker with a brand new `EntityResponse<String> bar()`.

The way to implement the change was so clear in my mind:

```java
class Router{ 
  @Bean
  RouterFunction<ServerResponse> helloWorldRouterFunction(OldHelloWorldService oldHelloWorldService) {
    return RouterFunctions.route()
        .route(RequestPredicates.path("/helloWorld/{option}"), x ->
            {
              String option = x.pathVariable("option");
              if (FeatureManager.isActive()) {
                return ServerResponse.ok().body(String.format("New implementation of Hello World! your option is: %s", option));
              } else {
                // FutureServerResponse is my own bad implementation of the ServerResponse interface
                return FutureServerResponse.from(oldHelloWorldService.futureFoo(Integer.parseInt(option)));
              }
            }
        )
        .build();
  }
}
```

Here's the implementation for `OldHelloWorldService::futureFoo`

```java
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
```

This feature lets my backend code decide what kind of `ResponseEntity` it will send, in the future. As you see, a smart function might for instance decide to either show a String message with an OK status, or give a `Location` header, and declare FOUND status, and not even give a String body at all. because the result type was a full fluid ResponseEntity, I had the power to do what I will.

Now with a `EntityResponse` you may still use a CompletionStage, but only as the actual entity. while building the `EntityResponse` I am required to give it a definitive `final` status. If it was OK, I can't decide it will be FOUND when my `CompletionStage` ran it's course.

The only problem with the above code, is that `org.springframework.web.servlet.function` does not contain the `FutureServerResponse` implementation I need. I created my own, and it works, but it feels hacky, And I wouldn't want it in my production code. 

Now I feel like the functionality should still be there somewhere, Why isn't there a FutureServerResponse that can decide in the future what it is? Is there a workaround to this problem maybe somehow (ab)using views?

To state the maybe not-so-obvious.. I am contemplating a move to reactive and WebFlux, but changing the entire runtime will have more dramatic implications on current production tenants, and making that move with a feature gate would be impossible because the urls would have to be shared between MVC and Flux.

It's a niche problem, and Functional Web MVC has little resources so I will appreciate greatly any help I can get.

I have created a [github companion](https://github.com/alexfeigin/functional-mvc-completion-stage-question) for this question.