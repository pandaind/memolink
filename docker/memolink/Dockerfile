# ─────────────────────────────────────────────────────────────────────────────
# MemoLink MCP Server — multi-stage Docker build
# ─────────────────────────────────────────────────────────────────────────────
# Build context: repo root  (docker-compose sets `context: .`)
#
# The MCP server runs in HTTP/SSE mode (Spring profile `http`) so LLM clients
# can connect over a stable TCP port instead of a stdio pipe.
#
# Runtime env:
#   MEMOLINK_NOTES_DIR   path inside the container where notes are mounted
#   MEMOLINK_HTTP_PORT   port the HTTP server listens on (default 8765)
#   HEADROOM_URL         internal URL of the headroom sidecar  e.g. http://headroom:8787
#   HEADROOM_ENABLED     true|false (default false — safe without headroom present)
#   HEADROOM_MIN_CHARS   minimum content length to trigger compression (default 300)
#   MEMOLINK_KEY_VSCODE  API key for VS Code / Cursor clients
#   MEMOLINK_KEY_CLAUDE  API key for Claude Desktop
#   MEMOLINK_KEY_CI      API key for CI / read-only bots
# ─────────────────────────────────────────────────────────────────────────────

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

# Install Maven
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

# ── Copy parent + module pom files first (cache dependency resolution layer) ──
COPY pom.xml                                         ./
COPY memolink-core/pom.xml                           memolink-core/
COPY memolink-viewer-starter/pom.xml                 memolink-viewer-starter/
COPY memolink-spring-ai-starter/pom.xml              memolink-spring-ai-starter/
COPY memolink-mcp-server/pom.xml                     memolink-mcp-server/
# examples module is declared in the reactor; copy its pom and submodules' poms so Maven can resolve the reactor graph
COPY examples/pom.xml                                examples/
COPY examples/viewer-app/pom.xml                    examples/viewer-app/
COPY examples/spring-ai-app/pom.xml                  examples/spring-ai-app/

# Download all dependencies (this layer is cached unless a pom.xml changes)
RUN mvn dependency:go-offline -P http --batch-mode -q

# ── Copy sources ───────────────────────────────────────────────────────────────
COPY memolink-core/src            memolink-core/src
COPY memolink-viewer-starter/src  memolink-viewer-starter/src
COPY memolink-spring-ai-starter/src memolink-spring-ai-starter/src
COPY memolink-mcp-server/src      memolink-mcp-server/src

# ── Build the MCP server fat jar with the http Maven profile ──────────────────
# -pl  : build only memolink-mcp-server
# -am  : also build modules it depends on (memolink-core, etc.)
# -P http : activates the http Maven profile (adds webmvc + security starters)
RUN mvn -pl memolink-mcp-server -am -P http \
        package -DskipTests --batch-mode -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app

# curl is needed for the Docker healthcheck
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root user — explicitly create home dir so Java's user.home resolves to a writable
# path, which EmbeddingService needs to extract model.onnx from the jar at startup.
RUN groupadd -r memolink \
    && useradd -r -d /home/memolink -g memolink memolink \
    && mkdir -p /home/memolink /notes \
    && chown memolink:memolink /home/memolink /notes /app

COPY --from=build --chown=memolink:memolink \
    /workspace/memolink-mcp-server/target/memolink-mcp-server-*.jar \
    app.jar

USER memolink

EXPOSE 8765

# ── Healthcheck ───────────────────────────────────────────────────────────────
HEALTHCHECK --interval=20s --timeout=10s --start-period=120s --retries=5 \
    CMD curl -sf http://localhost:8765/actuator/health | \
        grep -q '"status":"UP"' || exit 1

# Activate the `http` Spring profile so the SSE/HTTP transport starts.
# JVM flags:
#   -XX:+UseContainerSupport   — respect Docker CPU/memory limits
#   -XX:MaxRAMPercentage=75    — leave headroom for off-heap (ONNX model in memolink-core)
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar", \
  "--spring.profiles.active=http"]
