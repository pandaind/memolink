package ist.logic.mcp.config;

import ist.logic.mcp.tools.MemoLinkMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolsConfig {

    /**
     * Registers all {@code @Tool} methods on {@link MemoLinkMcpTools}
     * as MCP tool specifications, which Spring AI MCP server auto-detects.
     */
    @Bean
    public ToolCallbackProvider memolinkToolCallbacks(MemoLinkMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
