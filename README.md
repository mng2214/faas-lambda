# Lambda FaaS Platform – README

A lightweight **local Function-as-a-Service (FaaS)** engine built on **Java 21 Virtual Threads**,  
**Spring Boot**, and **Redis**.

This platform allows you to:

- register and run custom functions,
- enqueue events,
- process them asynchronously with workers,
- monitor metrics in real time,
- inspect logs and results,
- all through a fast, animated, modern **web UI**.

---

## ️ Multi-Threaded Engine with Virtual Threads

At the core of the system is a highly optimized execution engine based on  
**Project Loom (Virtual Threads)**, providing:

- a high-performance execution engine,
- a contract-based function system,
- an auto-discovery function registry,
- a Redis-backed asynchronous event queue,
- real-time metrics & dashboards,
- a modern web UI,
- dynamic function execution (simple, chain, streaming, webhook).

### Why scaling is rarely needed?

Because Java Virtual Threads are:

- extremely lightweight (hundreds of thousands per JVM),
- scheduled by the JVM instead of OS,
- perfect for IO-heavy asynchronous workloads.

This architecture allows the platform to handle high throughput **without needing distributed scaling**, load balancers,
or multi-node clusters.

In most real-world scenarios, a single instance is enough to:

- process jobs in parallel,
- handle spikes smoothly,
- serve as a local FaaS engine,
- run on Raspberry Pi or small servers.

### UI Represintation of FaaS with usefull data

![Capture](https://github.com/user-attachments/assets/f1b9e7ba-d4a1-4b59-89ec-94fcbff9ba4c)

### Local perfomance testing results for somple copmute function for 10 min

```java
        long sum = 0;
        final int[] a = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
        final int[] b = new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};

        for (int k : a) {
            for (int i : b) {
                sum += k + i;
            }
        }
```

# FaaS Engine Peak Performance Results

| Metric | Value |
|-------|-------|
| Total Requests | 100,000 |
| Throughput | ~9,688 req/sec |
| Duration | ~ 10 sec |
| Avg Response Time | 8 ms |
| Median (p50) | 8 ms |
| Max | 240 ms |
| Error Rate | 0.00% |

## Virtual Threads Engine

- Runs thousands of concurrent executions
- CPU-bound & IO-bound pools
- Retry logic
- Streaming and chained executions
- Graceful start/stop via @PostConstruct/@PreDestroy

  
## Features

### **1. Function Registration**

Your microservices can register functions by implementing a simple interface:

```java
public interface LambdaFunction {
    Object handle(Map<String, Object> payload);
}
```

Functions are auto‑discovered and stored in the Function Registry.

---

### **2. Event Queue (Redis)**

Each incoming request:

```
POST /events/{functionName}
```

Enqueues an event into Redis (`faas:events`).

Workers pick events one‑by‑one and execute the requested function.

---

### **3. Workers (Engine Module)**

- Multi‑threaded
- Virtual Threads support (Java 21)
- Automatic retries
- Error tracking
- Execution statistics

---

### **4. Metrics System**

UI shows:

- Queue Length
- Active Invocations
- Processed Count
- Error Count

Backend endpoint:

```
GET /functions/metrics
```

Returns:

```json
{
  "queueLength": 0,
  "activeInvocations": 1,
  "processedCount": 52,
  "errorCount": 2
}
```

---

### **5. Results & Error Logs per Function**

View results or errors:

```
GET /functions/results/{fnName}
GET /functions/errors/{fnName}
```

Data stored in Redis lists:

- `faas:results:{fn}`
- `faas:errors:{fn}`

---

### **6. Global Queue Viewer**

You can inspect the raw queue contents via:

```
GET /functions/list?key=faas:events
```

## Event Flow

1. Client posts:
```
POST /events/hello
```
2. Platform enqueues event to Redis.
3. Engine workers consume events.
4. Function executed via LocalLambdaFunction.
5. Results/errors stored in Redis.
6. UI displays metrics, results, queue state

---

## Web UI

The FaaS platform includes a full UI for:

- Viewing real‑time system metrics
- Browsing registered functions
- Inspecting results & errors
- Inspecting global queue
- Pagination
- Animation + auto‑refresh

### UI Default URL:

```
http://localhost:2222/functions/list
```

The UI file is located at:

```
faas-service/src/main/resources/templates/list.html
```

---

## Project Structure

```
faas-contracts     → contains LocalLambdaFunction and DTOs
faas-functions     → user-defined functions implementing the contract
faas-platform      → binds engine + Redis + metrics + registry
faas-engine        → workers, pipelines, processors
faas-service       → web UI + API controllers
```

---

## How it Works (Flow)

### 1 Service calls:

```
POST /events/hello
{
  "name": "Artur"
}
```

### 2 Platform enqueues:

```
LPUSH faas:events {...event...}
```

### 3 Workers consume events:

```
BRPOP faas:events
```

### 4 Engine executes:

```
FunctionRegistry.get("hello").handle(payload)
```

### 5 Results stored:

```
LPUSH faas:results:hello
```

### 6 UI displays everything in real‑time.

---

##  Running the Project

### Requirements

- Java 21
- Redis
- Gradle 8.5
- Docker (optional)

### Start Redis

```
docker run -p 6379:6379 redis
```

### Start Services

```
./gradlew bootRun
```

---

## Notes

- Workers start automatically using `@PostConstruct`
- Safe shutdown using `@PreDestroy`
- System is fully modular
- Any microservice can plug in functions
- Perfect for local FaaS, async execution, or job processing

---

##  Useful Endpoints

| Endpoint                          | Description      |
|-----------------------------------|------------------|
| `/ui/catalog`                     | UI page Catalog  |
| `/functions/metrics`              | System metrics   |
| `/events/{fn}`                    | Enqueue an event |
| `/functions/results/{fn}`         | Function results |
| `/functions/errors/{fn}`          | Function errors  |
| `/functions/list?key=faas:events` | Global queue     |

---

## Ongoing Development

The platform is actively evolving. 

---

## Author

Built with love and caffeine.  
Designed for fast local FaaS experimentation.

---

Enjoy!
