# Lambda FaaS Platform – README

A lightweight **local Function-as-a-Service (FaaS)** engine built on **Java 21 Virtual Threads**,  
**Spring Boot**, and **Redis**.

The platform is modular, extensible, and includes:

- a high-performance execution engine,
- a contract-based function system,
- an auto-discovery function registry,
- a Redis-backed asynchronous event queue,
- real-time metrics & dashboards,
- a modern web UI,
- dynamic function execution (simple, chain, streaming, webhook).

## Core Architecture

```
faas-contracts     → contains LocalLambdaFunction and DTOs
faas-functions     → user-defined functions implementing the contract
faas-platform      → binds engine + Redis + metrics + registry
faas-engine        → workers, pipelines, processors
faas-service       → web UI + API controllers
```

## Virtual Threads Engine

- Runs thousands of concurrent executions
- CPU-bound & IO-bound pools
- Retry logic
- Streaming and chained executions
- Graceful start/stop via @PostConstruct/@PreDestroy

## Function Registration

Functions implement the **contract module**:

```java
public interface LocalLambdaFunction {
    String getName();
    Map<String,Object> handle(Map<String,Object> input);
}
```

Functions live in **faas-functions** and are auto-registered by Spring.

## Event Flow

1. Client posts:
```
POST /events/hello
```
2. Platform enqueues event to Redis.
3. Engine workers consume events.
4. Function executed via LocalLambdaFunction.
5. Results/errors stored in Redis.
6. UI displays metrics, results, queue state.

## Metrics

```
GET /functions/metrics
```

## Useful Endpoints

| Endpoint | Description |
|---------|-------------|
| `/ui/catalog` | UI page |
| `/events/{fn}` | Enqueue event |
| `/functions/results/{fn}` | Results |
| `/functions/errors/{fn}` | Errors |
| `/functions/list?key=faas:events` | View queue |
