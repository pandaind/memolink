# Spring Boot

tags: #java #framework #spring #boot

Spring Boot is an opinionated framework built on top of [[spring-framework]] that
makes it easy to create stand-alone, production-grade [[java]] applications with
minimal configuration.

## Key Features

- **Auto-configuration** – detects classpath dependencies and configures beans
  automatically (e.g. adding `spring-boot-starter-web` auto-configures an embedded
  [[tomcat]] server).
- **Starter POMs** – curated dependency bundles for common concerns such as web,
  data, security, and messaging.
- **Embedded server** – no WAR deployment needed; run with `java -jar`.
- **Actuator** – production-ready endpoints for health, metrics, and environment
  inspection.

## Common Starters

| Starter | Purpose |
|---------|---------|
| `spring-boot-starter-web` | Spring MVC + embedded Tomcat |
| `spring-boot-starter-data-jpa` | JPA / Hibernate + HikariCP |
| `spring-boot-starter-security` | Spring Security |
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ |

## Integration with AI

[[spring-ai]] extends Spring Boot with auto-configured clients for LLM providers
(OpenAI, Anthropic, Ollama…) and a `ChatClient` builder that follows the same
familiar Spring idiom.

See also: [[kafka]] for event-driven architectures.
