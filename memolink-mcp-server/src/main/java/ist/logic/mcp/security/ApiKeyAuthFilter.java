package ist.logic.mcp.security;

import ist.logic.mcp.config.AuthProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Servlet filter that validates API keys for the MCP HTTP/SSE endpoint.
 *
 * <h3>Key lookup order</h3>
 * <ol>
 *   <li>{@code X-API-Key: sk-...} header</li>
 *   <li>{@code Authorization: Bearer sk-...} header</li>
 * </ol>
 *
 * <h3>Authentication result</h3>
 * On success, sets a Spring Security {@link UsernamePasswordAuthenticationToken}
 * with the client name as principal and its configured roles as authorities
 * (e.g. {@code ROLE_READ}, {@code ROLE_WRITE}).
 *
 * <h3>Failure response</h3>
 * Returns {@code 401 Unauthorized} with a JSON body:
 * <pre>{"error": "Unauthorized", "message": "..."}</pre>
 * Intentionally does NOT reveal which key was wrong or whether the key exists.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String HEADER_API_KEY   = "X-API-Key";
    private static final String HEADER_AUTH      = "Authorization";
    private static final String BEARER_PREFIX    = "Bearer ";

    /** Pre-built lookup: raw key value → Authentication token. */
    private final Map<String, UsernamePasswordAuthenticationToken> keyRegistry;
    
    private final SecurityContextRepository securityContextRepository = new RequestAttributeSecurityContextRepository();

    public ApiKeyAuthFilter(AuthProperties authProperties) {
        this.keyRegistry = authProperties.getClients().stream()
                .filter(c -> c.getKey() != null && !c.getKey().isBlank())
                .collect(Collectors.toMap(
                        AuthProperties.Client::getKey,
                        c -> {
                            var authorities = c.getRoles().stream()
                                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r.toUpperCase()))
                                    .collect(Collectors.toList());
                            var token = new UsernamePasswordAuthenticationToken(
                                     c.getName(), null, authorities);
                            return token;
                        }
                ));
        log.info("API key auth filter active — {} client(s) registered",
                authProperties.getClients().size());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        return "/actuator/health".equals(path) || "/actuator/health/".equals(path);
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String key = extractKey(request);

        if (key == null) {
            rejectUnauthorized(response, "Missing API key. Provide X-API-Key header or Authorization: Bearer <key>.");
            return;
        }

        UsernamePasswordAuthenticationToken auth = keyRegistry.get(key);
        if (auth == null) {
            log.warn("Rejected request from {} — invalid API key", request.getRemoteAddr());
            rejectUnauthorized(response, "Invalid API key.");
            return;
        }

        log.debug("Authenticated client '{}' with roles {}", auth.getName(), auth.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        chain.doFilter(request, response);
    }

    private static String extractKey(HttpServletRequest request) {
        // 1. X-API-Key header
        String key = request.getHeader(HEADER_API_KEY);
        if (key != null && !key.isBlank()) return key.trim();

        // 2. Authorization: Bearer <key>
        String authHeader = request.getHeader(HEADER_AUTH);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private static void rejectUnauthorized(HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}");
    }
}
