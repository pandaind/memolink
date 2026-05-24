# Spring Framework

tags: #java #framework #spring #ioc

The Spring Framework is the foundational layer beneath [[spring-boot]].
It provides the core dependency-injection container (IoC), AOP support,
transaction management, and a rich set of integration APIs.

## Core Concepts

- **IoC Container** – manages bean lifecycle and wires dependencies declared via
  `@Component`, `@Service`, `@Repository`, or `@Configuration` + `@Bean`.
- **AOP** – cross-cutting concerns (logging, transactions, security) applied via
  proxy-based aspects.
- **ApplicationContext** – enriched IoC container with event publishing, i18n,
  and resource loading.

## Modules

| Module | Responsibility |
|--------|----------------|
| `spring-core` | IoC container fundamentals |
| `spring-context` | ApplicationContext, events, SpEL |
| `spring-web` | HTTP abstractions |
| `spring-webmvc` | Spring MVC dispatcher |
| `spring-tx` | Declarative transaction management |

## Relationship to Spring Boot

[[spring-boot]] wraps the Spring Framework with opinionated auto-configuration
so developers can focus on business logic rather than XML or `@Configuration`
boilerplate.

See also: [[java]], [[kafka]].
