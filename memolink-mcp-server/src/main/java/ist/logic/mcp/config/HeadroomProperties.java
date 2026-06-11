package ist.logic.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code memolink.headroom.*} configuration properties.
 *
 * <pre>
 * memolink:
 *   headroom:
 *     url:       http://headroom:8787   # empty = disabled
 *     enabled:   true
 *     min-chars: 300
 *     timeout-seconds: 30
 * </pre>
 */
@ConfigurationProperties(prefix = "memolink.headroom")
public class HeadroomProperties {

    /** Base URL of the headroom sidecar.  Empty string disables compression. */
    private String url = "";

    /**
     * Master switch.  Set to {@code false} to bypass compression entirely
     * without removing the sidecar.
     */
    private boolean enabled = false;

    /**
     * Minimum content length in characters.
     * Content shorter than this threshold is returned unchanged (fast-path).
     */
    private int minChars = 300;

    /** HTTP timeout for each /compress call in seconds. */
    private int timeoutSeconds = 30;

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String getUrl()             { return url; }
    public void   setUrl(String url)   { this.url = url; }

    public boolean isEnabled()                   { return enabled; }
    public void    setEnabled(boolean enabled)   { this.enabled = enabled; }

    public int  getMinChars()              { return minChars; }
    public void setMinChars(int minChars)  { this.minChars = minChars; }

    public int  getTimeoutSeconds()                    { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds)  { this.timeoutSeconds = timeoutSeconds; }

    /** Returns true when compression can be attempted. */
    public boolean isActive() {
        return enabled && url != null && !url.isBlank();
    }
}
