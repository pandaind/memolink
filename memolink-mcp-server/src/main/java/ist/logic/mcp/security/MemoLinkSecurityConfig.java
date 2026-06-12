package ist.logic.mcp.security;

import ist.logic.mcp.config.AuthProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security configuration for MCP HTTP/SSE mode.
 *
 * <p>Only activated when <em>both</em>:
 * <ol>
 *   <li>The application is running as a web app ({@code spring.profiles.active=http})</li>
 *   <li>{@code memolink.auth.enabled=true}</li>
 * </ol>
 *
 * <h3>Role mapping</h3>
 * <pre>
 * ROLE_READ  → GET  /mcp/** (SSE stream, tool/prompt listing)
 * ROLE_WRITE → POST /mcp/** (tool invocation, mutations)
 * </pre>
 *
 * <h3>Stateless</h3>
 * No sessions. Every request must carry a valid API key. The filter is
 * inserted before Spring Security's own username/password filter.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "memolink.auth.enabled", havingValue = "true")
@EnableConfigurationProperties(AuthProperties.class)
public class MemoLinkSecurityConfig {

    private final AuthProperties authProperties;

    public MemoLinkSecurityConfig(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Stateless API — no sessions, no CSRF
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())

            // Insert API key filter before Spring's own auth filter
            .addFilterBefore(new ApiKeyAuthFilter(authProperties),
                    UsernamePasswordAuthenticationFilter.class)

            .authorizeHttpRequests(auth -> auth
                // Health/actuator endpoints are public
                .requestMatchers("/actuator/health").permitAll()

                // Streamable HTTP — GET opens SSE session or polls, POST invokes tools
                .requestMatchers(HttpMethod.GET,    "/mcp").hasRole("READ")
                .requestMatchers(HttpMethod.POST,   "/mcp").hasRole("WRITE")
                // DELETE terminates a session — allow READ-level clients to disconnect
                .requestMatchers(HttpMethod.DELETE, "/mcp").hasRole("READ")

                // Everything else requires at least READ
                .anyRequest().hasRole("READ")
            )

            // Return JSON 401 instead of a redirect to a login page
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"error\":\"Unauthorized\",\"message\":\"API key required.\"}");
                })
                .accessDeniedHandler((request, response, deniedException) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"error\":\"Forbidden\",\"message\":\"Insufficient role for this operation.\"}");
                })
            );

        return http.build();
    }
}
