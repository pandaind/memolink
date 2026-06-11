package com.example.viewer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot application that activates the MemoLink viewer starter.
 *
 * <p>The starter auto-configures:
 * <ul>
 *   <li>A {@code KnowledgeGraph} built by scanning all {@code .md} files under
 *       {@code memolink.vault-dir} (see {@code application.yml}).</li>
 *   <li>REST endpoints at {@code /memolink/api/graph}, {@code /memolink/api/search},
 *       {@code /memolink/api/files/{id}}, and {@code /memolink/api/traverse/{id}}.</li>
 *   <li>A static Cytoscape.js UI served from {@code /index.html}.</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   mvn spring-boot:run
 * </pre>
 * then open <a href="http://localhost:8080">http://localhost:8080</a>.
 */
@SpringBootApplication
public class ViewerApp {

    public static void main(String[] args) {
        SpringApplication.run(ViewerApp.class, args);
    }
}
