# Java

tags: #java #jvm #language

Java is a statically-typed, object-oriented language running on the JVM.
It is the primary language for [[spring-framework]] and [[spring-boot]].

## Version Highlights

| Version | Notable Feature |
|---------|----------------|
| Java 8  | Lambdas, Streams, Optional |
| Java 11 | LTS; `var` in lambda params |
| Java 17 | LTS; sealed classes, records |
| Java 21  | LTS; virtual threads (Project Loom), pattern matching |

## Virtual Threads (Java 21)

Virtual threads drastically reduce the overhead of blocking I/O and are a natural
fit for [[spring-boot]] web applications – enable them with a single property:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

## Build Tools

- **Maven** – convention-based; uses `pom.xml`.
- **Gradle** – flexible DSL-based build; Kotlin or Groovy scripts.

See also: [[spring-framework]].
