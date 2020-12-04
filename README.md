[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.zopa/ktor-opentracing/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.zopa/ktor-opentracing)
![GitHub](https://img.shields.io/github/license/zopaUK/ktor-opentracing.svg?color=green&style=popout)
[![Unit Tests Actions Status](https://github.com/zopaUK/ktor-opentracing/workflows/Unit%20Tests/badge.svg)](https://github.com/{userName}/{repoName}/actions)

# Ktor OpenTracing Instrumentation

Library of Ktor features for OpenTracing instrumention of HTTP servers and clients. 

## Installation 
 
### 
 
### Maven
Add the following dependency to your `pom.xml`:
```xml
<dependency>
 <groupId>com.zopa</groupId>
 <artifactId>ktor-opentracing</artifactId>
 <version>VERSION_NUMBER</version>
</dependency>
```
 
### Gradle
Add the following to your `dependencies` in your `build.gradle` 
 
```
implementation "com.zopa:ktor-opentracing:VERSION_NUMBER"
```

## Examples

For a simple example of ktor app instrumented with OpenTracing, see [ktor-opentracing-example](https://github.com/fstien/ktor-opentracing-example). This app uses span names passed explicitely to the `span` inline function. 

For automatic span naming using the class and method name, see [ktor-opentracing-span-naming-demo](https://github.com/fstien/ktor-opentracing-span-naming-demo).

## Usage

### Server Spans
Install the `OpenTracingServer` feature as follows in a module: 

```kotlin 
fun Application.mymodule() {
    install(OpenTracingServer)
}
```

The feature uses the tracer registered in [GlobalTracer](https://opentracing.io/guides/java/tracers/), which uses the `ThreadContextElementScopeManager`.
 This is needed to propagate the tracing context in the coroutine context of the calls.
 For example, you can instantiate and register a [Jaeger](https://github.com/jaegertracing/jaeger-client-java) tracer in the module before the call to `install` as follows:
 

```kotlin
val tracer: Tracer = config.tracerBuilder
    .withScopeManager(CoroutineThreadLocalScopeManager())
    .build()

GlobalTracer.registerIfAbsent(tracer)
```
 
At this stage, the application will be creating a single span for the duration of the request. 
If the incoming request has tracing tracing context in its HTTP headers, then the span will be a child of the one in that context. 
Otherwise, the feature will start a new trace. 


### Individual code blocks
To get a more detailed view of requests, we might want to instrument individual code blocks as child spans. 
We could start a new child span using the tracer instance directly, however this would be too intrusive and verbose.
Instead, we can use the `span` inline function as follows. 

```kotlin
class UserRepository {
    fun getUser(id: UUID): User = span("<operation-name>") {
        setTag("UserId", id)
    
        ... database call ...
       
        return user
    }
}
```

`span` is passed an operation name and a code block, which has the `Span` as a receiver. 
This means that any method on the `Span` interface an be called in the block, such as `setTag`, `log` or `getBaggageItem`. 

### Concurrency with async

Concurrent operations using `async` can break in-process context propagation which uses coroutine context, leading to spans with incorrect parents.
To solve this issue, replace the calls to `async` with `asyncTraced`. This will pass the correct tracing context to the new coroutines. 

```kotlin
val scrapeResults = urls.map { url -> 
    asyncTraced { 
        httpClient.get(url)
    }
    .awaitAll()
}
```
If your application starts new coroutines using any other coroutine builder, add `tracingContext()` to the context of the next coroutines. For example:
```kotlin
launch(coroutineContext + tracingContext()) {
    span("TracedCoroutine") {
        sleep(100)
    }
}
```


### Client Spans
If your application calls another service using the Ktor HTTP client, you can install the `OpenTracingClient` feature on the client to create client spans: 

```kotlin
val client = HttpClient(Apache) {
    install(OpenTracingClient)
}
```
The outgoing HTTP headers from this client will contain the trace context of the client span. 
This allows the service that is called to create child spans of this client span. 

We recommend using this feature in a server that has `OpenTracingServer` installed.


## Configuration 

Your application might be serving static content (such as k8s probes), for which you do not to create traces. 
You can filter these out as follows:
```kotlin
install(OpenTracingServer) {
    filter { call -> call.request.path().startsWith("/_probes") }
}
```


#### [MIT](./LICENSE) License