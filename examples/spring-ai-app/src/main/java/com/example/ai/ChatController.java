package com.example.ai;

import ist.logic.ai.tools.MemoLinkAiTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple REST endpoint that forwards a user message to an OpenAI model with
 * full access to the MemoLink knowledge-graph tools.
 *
 * <p>The LLM can autonomously call:
 * <ul>
 *   <li>{@code searchMdFiles(query)} – Lucene full-text search.</li>
 *   <li>{@code getRelatedMdFiles(fileId)} – graph-neighbour expansion.</li>
 *   <li>{@code getMdFileContent(fileId)} – read raw Markdown.</li>
 *   <li>{@code traverseGraph(fileId, depth)} – BFS traversal.</li>
 * </ul>
 *
 * <p>Example:
 * <pre>
 *   GET /chat?message=Explain+how+Kafka+relates+to+Spring+Boot+in+my+notes
 * </pre>
 */
@RestController
public class ChatController {

    private final ChatClient      chatClient;
    private final MemoLinkAiTools  memoLinkAiTools;

    public ChatController(ChatClient.Builder builder, MemoLinkAiTools memoLinkAiTools) {
        // System prompt instructs the model to use the graph tools before answering
        this.chatClient = builder
                .defaultSystem("""
                        You are a knowledgeable assistant with access to the user's
                        personal Markdown knowledge graph.
                        Before answering any question, search the graph using the
                        available tools to find relevant notes and ground your answer
                        in the actual content found there.
                        """)
                .build();
        this.memoLinkAiTools = memoLinkAiTools;
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return chatClient.prompt()
                .user(message)
                .tools(memoLinkAiTools)
                .call()
                .content();
    }
}
