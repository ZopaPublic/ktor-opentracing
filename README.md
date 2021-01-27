 [![Download](https://api.bintray.com/packages/fstien/ktor-opentracing/ktor-opentracing/images/download.svg)](https://bintray.com/fstien/ktor-opentracing/ktor-opentracing/_latestVersion)
![GitHub](https://img.shields.io/github/license/zopaUK/ktor-opentracing.svg?color=green&style=popout)
[![Unit Tests Actions Status](https://github.com/zopaUK/ktor-opentracing/workflows/Unit%20Tests/badge.svg)](https://github.com/{userName}/{repoName}/actions)

# Ktor OpenTracing Instrumentation

Library of Ktor features for OpenTracing instrumentation of HTTP servers and clients. 

## Usage

### Server Spans
Install the `OpenTracingServer` feature as follows in a module: 

```kotlin 
install(OpenTracingServer)
```

The feature uses the tracer registered in [GlobalTracer](https://opentracing.io/guides/java/tracers/), which uses the `ThreadContextElementScopeManager`.
 This is needed to propagate the tracing context in the coroutine context of the calls.
 For example, you can instantiate and register a [Jaeger](https://github.com/jaegertracing/jaeger-client-java) tracer in the module before the call to `install` as follows:
 

```kotlin
val tracer: Tracer = config.tracerBuilder
    .withScopeManager(ThreadContextElementScopeManager())
    .build()

GlobalTracer.registerIfAbsent(tracer)
```
 
At this stage, the application will be creating a single span for the duration of the request. 
If the incoming request has tracing context in its HTTP headers, then the span will be a child of the one in that context. 
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

`span` is passed an operation name and an anonymous lambda, which has the `Span` as a receiver object. 
This means that you can call `setTag`, `log`, `getBaggageItem` (or any method on the `Span` interface).

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
Underneath the hood, `asyncTraced` is adding the current tracing context to the coroutine context using a call to `tracingContext()`. You can add it yourself by calling `async(tracingContext())`. To `launch` a new coroutine with the tracing context, call `launchTraced`. 


### Client Spans
If your application calls another service using the Ktor HTTP client, you can install the `OpenTracingClient` feature on the client to create client spans: 

```kotlin
install(OpenTracingClient)
```
The outgoing HTTP headers from this client will contain the trace context of the client span. 
This allows the service that is called to create child spans of this client span. 

We recommend using this feature in a server that has `OpenTracingServer` installed.


## Configuration 

### filter
Your application might be serving static content (such as k8s probes), for which you do not want to create traces. 
You can filter these out as follows:
```kotlin
install(OpenTracingServer) {
    filter { call -> call.request.path().startsWith("/_probes") }
}
```

### replaceInPathAndTagSpan
When a request path contains an id, you can replace it with a constant string in the span operation name. This ensures that requests for different ids have the same span operation name. 
The value of the id is then tagged on the span. 
```kotlin
install(OpenTracingServer) {
    replaceInPathAndTagSpan(Regex("""[0-9]{8}-[0-9]{4}"""), "customId")
}
```
In the above example, `/path/12345678-1234` would be recorded as `/path/<customId>` with the tag `customId=12345678-1234`.

Note that UUIDs are already tagged and replaced by default.

## Installation 
Using [jcenter](https://bintray.com/bintray/jcenter).
 
### Maven
Add the following dependency to your `pom.xml`:
```xml
<dependency>
 <groupId>com.github.fstien</groupId>
 <artifactId>ktor-opentracing</artifactId>
 <version>VERSION_NUMBER</version>
</dependency>
```
 
### Gradle
Add the following to your `dependencies` in your `build.gradle` 
 
```
implementation "com.github.fstien:ktor-opentracing:VERSION_NUMBER"
```

## Examples

- For a simple example of ktor app instrumented with OpenTracing, see [ktor-opentracing-example](https://github.com/fstien/ktor-opentracing-example). This app uses span names passed explicitly to the `span` inline function. 

- For automatic span naming using the class and method name, see [ktor-opentracing-span-naming-demo](https://github.com/fstien/ktor-opentracing-span-naming-demo).

## Related Projects

- For Ktor services using [kotlin-logging](https://github.com/MicroUtils/kotlin-logging), you can use [kotlin-logging-opentracing-decorator](https://github.com/fstien/kotlin-logging-opentracing-decorator) to enrich your spans with logs. 
- If you are using [Exposed](https://github.com/JetBrains/Exposed), you can use [Exposed-OpenTracing](https://github.com/fstien/Exposed-OpenTracing) to instrument database transactions.
