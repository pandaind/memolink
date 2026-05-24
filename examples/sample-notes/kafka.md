# Apache Kafka

tags: #messaging #streaming #distributed

Apache Kafka is a distributed event-streaming platform used for high-throughput,
fault-tolerant publish-subscribe messaging.

## Core Concepts

- **Topic** – named, partitioned log of records.
- **Producer** – writes records to a topic.
- **Consumer** – reads records from one or more topics, grouped into consumer groups.
- **Broker** – Kafka server; multiple brokers form a cluster.

## Spring Integration

[[spring-boot]] provides `spring-kafka` auto-configuration.  
Declare a listener with `@KafkaListener`:

```java
@KafkaListener(topics = "notes-events")
public void onEvent(String payload) {
    // process event
}
```

## Use Cases

- Event-driven microservices that emit domain events on every save of a
  entity.
- Real-time pipelines feeding knowledge-graph update events.
- Log aggregation across [[spring-framework]] services.

## Key Properties

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: my-app
      auto-offset-reset: earliest
```

See also: [[spring-boot]], [[spring-framework]].
