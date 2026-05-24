# Spring AI

tags: #java #spring #ai #llm

Spring AI brings the Spring programming model to AI/LLM integrations. It provides
auto-configured `ChatClient` beans for providers such as OpenAI, Anthropic,
Google Gemini, and Ollama.

## Core Abstraction

```java
@Bean
CommandLineRunner run(ChatClient.Builder builder) {
    ChatClient chat = builder.build();
    return args -> {
        String answer = chat.prompt()
            .user("Summarise Spring Boot in one sentence.")
            .call()
            .content();
        System.out.println(answer);
    };
}
```

## Tool Calling

LLMs can invoke [[java]]-backed tools annotated with `@Tool`:

```java
@Tool(description = "Return the current UTC time.")
public String currentTime() {
    return Instant.now().toString();
}
```

Pass tool objects at call time:

```java
chat.prompt().user(message).tools(myTools).call().content();
```

## Supported Providers

- OpenAI / Azure OpenAI
- Anthropic Claude
- Google Gemini
- Ollama (local models)
- Mistral AI

## MCP Integration

Spring AI also supports the Model Context Protocol ([[mcp]]), letting LLMs call
tools exposed over a standardised JSON-RPC transport. Combine with [[spring-boot]]'s
MCP server auto-configuration to host tools as an MCP service.

See also: [[spring-boot]], [[spring-framework]].
