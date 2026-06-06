package ist.logic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Authentication configuration for the MemoLink MCP server.
 *
 * Relevant only when running in HTTP/SSE mode (Spring profile {@code http}).
 * In stdio mode the OS process model is the trust boundary — no API keys needed.
 *
 * <pre>
 * memolink:
 *   auth:
 *     enabled: true
 *     clients:
 *       - name: vscode
 *         key: "sk-vscode-changeme"
 *         roles:
 *           - READ
 *           - WRITE
 *       - name: claude-desktop
 *         key: "sk-claude-changeme"
 *         roles:
 *           - READ
 *       - name: ci-bot
 *         key: "sk-ci-changeme"
 *         roles:
 *           - READ
 * </pre>
 *
 * Roles:
 * <ul>
 *   <li>{@code READ}  — search, list, get, traverse, path-finding, memory summary</li>
 *   <li>{@code WRITE} — create, update, delete, set_importance, gather_reflection</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "memolink.auth")
public class AuthProperties {

    /** When false the security filter is not registered (stdio mode default). */
    private boolean enabled = false;

    private List<Client> clients = new ArrayList<>();

    public boolean isEnabled()            { return enabled; }
    public void    setEnabled(boolean e)  { this.enabled = e; }

    public List<Client> getClients()                    { return clients; }
    public void         setClients(List<Client> clients) { this.clients = clients; }

    public static class Client {
        private String       name;
        private String       key;
        private List<String> roles = List.of("READ");

        public String       getName()               { return name; }
        public void         setName(String n)        { this.name = n; }

        public String       getKey()                { return key; }
        public void         setKey(String k)         { this.key = k; }

        public List<String> getRoles()              { return roles; }
        public void         setRoles(List<String> r) { this.roles = r; }
    }
}
