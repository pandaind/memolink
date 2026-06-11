package com.example.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application that activates the MemoLink Spring AI starter.
 *
 * <p>The starter auto-configures:
 * <ul>
 *   <li>A {@code KnowledgeGraph} scanned from {@code memolink.vault-dir}.</li>
 *   <li>A {@code MemoLinkAiTools} bean whose {@code @Tool} methods let the LLM
 *       search, traverse, and read your Markdown notes.</li>
 * </ul>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Set the {@code OPENAI_API_KEY} environment variable.</li>
 *   <li>Adjust {@code memolink.vault-dir} in {@code application.yml}.</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   OPENAI_API_KEY=sk-... mvn spring-boot:run
 * </pre>
 * then query:
 * <pre>
 *   curl "http://localhost:8081/chat?message=What+do+my+notes+say+about+Kafka?"
 * </pre>
 */
@SpringBootApplication
public class SpringAiApp {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiApp.class, args);
    }
}
