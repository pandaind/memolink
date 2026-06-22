#!/usr/bin/env python3
"""
MemoLink Vault Writer Agent
============================
Intelligently uploads skills, instructions, context and memory notes
to MemoLink with rich metadata optimized for semantic and keyword search.
Memories are namespaced under their project folder: {project}/{type}/{title}.md

USAGE
-----
  # Upload a markdown file (project auto-detected from git):
  python3 agents/vault_writer.py --file path/to/note.md --type skill

  # Explicit project name:
  python3 agents/vault_writer.py --file path/to/note.md --type skill --project my-app

  # Write inline content:
  python3 agents/vault_writer.py --title "Docker Fix" --body "..." --type memory

  # Force-update even if a similar memory already exists:
  python3 agents/vault_writer.py --file note.md --type instruction --force-update

  # Extra tags on top of auto-extracted ones:
  python3 agents/vault_writer.py --file note.md --type context --tags "java,spring,api"

  # Explicit importance override (0-10):
  python3 agents/vault_writer.py --file note.md --type skill --importance 9

ENVIRONMENT VARIABLES
---------------------
  MEMOLINK_URL              MemoLink base URL  (default: http://localhost:8765)
  MEMOLINK_KEY_VSCODE       API key            (default: sk-vscode-changeme)
  MEMOLINK_SCORE_THRESHOLD  Duplicate threshold (default: 0.35)
  MEMOLINK_PROJECT          Default project name (overridden by --project flag)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import textwrap
import subprocess
from pathlib import Path
from typing import Any

try:
    import requests
except ImportError:
    sys.exit("❌  'requests' is not installed.\n   Run:  pip install requests")

# ─── Configuration ────────────────────────────────────────────────────────────

MEMOLINK_URL    = os.environ.get("MEMOLINK_URL",          "http://localhost:8765")
MEMOLINK_KEY    = os.environ.get("MEMOLINK_KEY_VSCODE",   "sk-vscode-changeme")
MCP_ENDPOINT    = f"{MEMOLINK_URL}/mcp"
DUP_THRESHOLD   = float(os.environ.get("MEMOLINK_SCORE_THRESHOLD", "0.35"))
DEFAULT_PROJECT = os.environ.get("MEMOLINK_PROJECT", "")

# Per-type defaults:  folder prefix, default importance, auto base-tags
TYPE_CONFIG: dict[str, dict[str, Any]] = {
    "skill": {
        "folder":     "skills",
        "importance": 8,
        "base_tags":  ["skill", "reusable"],
    },
    "instruction": {
        "folder":     "instructions",
        "importance": 9,
        "base_tags":  ["instruction", "agent-rule"],
    },
    "context": {
        "folder":     "context",
        "importance": 6,
        "base_tags":  ["context", "reference"],
    },
    "memory": {
        "folder":     "memory",
        "importance": 5,
        "base_tags":  ["memory", "log"],
    },
}

# ─── MCP HTTP Client ──────────────────────────────────────────────────────────

class McpClient:
    """
    Thin JSON-RPC 2.0 client for MemoLink's Streamable HTTP MCP transport.

    Protocol flow:
      1. POST /mcp  →  initialize  (obtains Mcp-Session-Id header)
      2. POST /mcp  →  tools/call  (subsequent requests reuse the session header)

    Spring AI MCP may reply with either plain JSON or text/event-stream SSE.
    Both formats are handled transparently.
    """

    def __init__(self, endpoint: str, api_key: str) -> None:
        self.endpoint = endpoint
        self.headers  = {
            "X-API-Key":    api_key,
            "Content-Type": "application/json",
            "Accept":       "application/json, text/event-stream",
        }
        self._session_id: str | None = None
        self._req_id = 0

    # ── Session ───────────────────────────────────────────────────────────────

    def initialize(self) -> dict:
        rpc = self._rpc("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities":    {},
            "clientInfo":      {"name": "vault-writer-agent", "version": "1.0"},
        })
        resp = self._post(rpc)
        sid  = resp.headers.get("Mcp-Session-Id")
        if sid:
            self._session_id           = sid
            self.headers["Mcp-Session-Id"] = sid
        return self._decode(resp)

    # ── Tool call ─────────────────────────────────────────────────────────────

    def call(self, tool: str, args: dict) -> Any:
        """Call a MemoLink MCP tool and return the unwrapped text content."""
        if not self._session_id:
            self.initialize()

        rpc  = self._rpc("tools/call", {"name": tool, "arguments": args})
        resp = self._post(rpc)
        data = self._decode(resp)

        # JSON-RPC error block
        if "error" in data:
            raise RuntimeError(f"MCP error from '{tool}': {data['error']}")

        # Unwrap Spring AI MCP tool result:
        # {"result": {"content": [{"type": "text", "text": "..."}], "isError": false}}
        result = data.get("result", data)
        if isinstance(result, dict):
            content = result.get("content", [])
            if isinstance(content, list) and content:
                text = content[0].get("text", "")
                # Try to parse JSON; fall back to raw string
                try:
                    return json.loads(text)
                except (json.JSONDecodeError, TypeError):
                    return text
        return result

    # ── Internal helpers ──────────────────────────────────────────────────────

    def _rpc(self, method: str, params: dict) -> dict:
        self._req_id += 1
        return {"jsonrpc": "2.0", "id": self._req_id, "method": method, "params": params}

    def _post(self, payload: dict) -> requests.Response:
        resp = requests.post(self.endpoint, json=payload, headers=self.headers, timeout=30)
        resp.raise_for_status()
        return resp

    @staticmethod
    def _decode(resp: requests.Response) -> dict:
        ct = resp.headers.get("Content-Type", "")
        if "text/event-stream" in ct:
            # SSE: scan for the last `data:` line that is valid JSON
            for line in reversed(resp.text.splitlines()):
                line = line.strip()
                if line.startswith("data:"):
                    try:
                        return json.loads(line[5:].strip())
                    except json.JSONDecodeError:
                        continue
            return {}
        return resp.json()


# ─── Content Enrichment ───────────────────────────────────────────────────────

# Matches  #tag  in markdown body (not headings, not URLs)
_TAG_RE     = re.compile(r"(?<![/\w])#([a-zA-Z][a-zA-Z0-9_-]*)")
# Matches  [[Link Text]]  or  [[link|type]]
_WIKILINK_RE = re.compile(r"\[\[([^\]|]+)(?:\|[^\]]+)?]]")
# Used to slugify a title → file_id
_SLUGIFY_RE  = re.compile(r"[^a-z0-9]+")


def slugify(text: str) -> str:
    return _SLUGIFY_RE.sub("-", text.lower()).strip("-")


def extract_tags_from_body(body: str) -> list[str]:
    """Extract #tags already embedded in the markdown content."""
    return [m.lower() for m in _TAG_RE.findall(body)]


def extract_wiki_links(body: str) -> list[str]:
    """Extract [[wiki links]] from body and normalize them to file IDs."""
    links = []
    for raw in _WIKILINK_RE.findall(body):
        slug = slugify(raw.strip())
        if slug:
            links.append(f"{slug}.md")
    return links


def derive_title(content: str, fallback: str) -> str:
    """Return the first H1 heading in the content, or fallback."""
    for line in content.splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return fallback


def derive_summary_line(body: str) -> str:
    """
    Return the first non-heading, non-empty prose line of the body.
    This becomes the first line of the stored note and is critical for
    keyword search because Lucene boosts earlier tokens.
    """
    for line in body.splitlines():
        line = line.strip()
        if line and not line.startswith("#") and not line.startswith("```"):
            return line
    return ""


def build_search_optimized_body(title: str, note_type: str, original_body: str, extra_tags: list[str]) -> str:
    """
    Prepend a TL;DR summary block to the body so that:
      - The Lucene BM25 title/headings fields are richly populated.
      - The ONNX semantic embedding covers the key concepts upfront (it only
        reads the first 512 chars of body).
      - Human readers instantly understand the note purpose.
    """
    summary = derive_summary_line(original_body)
    type_label = note_type.upper()

    header = textwrap.dedent(f"""\
        > **[{type_label}]** {summary}

    """) if summary else f"> **[{type_label}]**\n\n"

    return header + original_body


def build_metadata(note_type: str, source_file: str | None) -> dict[str, str]:
    """Build frontmatter metadata dict for richer faceted search."""
    from datetime import date
    meta: dict[str, str] = {
        "type": note_type,
        "created": str(date.today()),
    }
    if source_file:
        meta["source_file"] = Path(source_file).name
    return meta


# ─── Project Name Detection ─────────────────────────────────────────────────

def detect_project_name(cwd: str | None = None) -> str:
    """
    Detect the project name in priority order:
      1. MEMOLINK_PROJECT env var
      2. Git remote URL basename (e.g. git@github.com:user/my-app.git → my-app)
      3. Current working directory name
    Returns a slugified project name.
    """
    if DEFAULT_PROJECT:
        return slugify(DEFAULT_PROJECT)
    try:
        url = subprocess.check_output(
            ["git", "remote", "get-url", "origin"],
            cwd=cwd, stderr=subprocess.DEVNULL, text=True
        ).strip()
        # e.g. https://github.com/user/my-app.git  or  git@github.com:user/my-app.git
        name = url.rstrip("/").rstrip(".git").rsplit("/", 1)[-1].rsplit(":", 1)[-1]
        if name:
            return slugify(name)
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    return slugify(Path(cwd or os.getcwd()).name)


# ─── Duplicate Detection ──────────────────────────────────────────────────────

def find_existing(client: McpClient, title: str, threshold: float) -> tuple[str | None, float]:
    """
    Search MemoLink for an existing memory similar to title.
    Returns (file_id, score) of the best hit above threshold, or (None, 0.0).
    """
    results = client.call("search_memories", {"query": title})
    if not isinstance(results, list):
        return None, 0.0
    for hit in results:
        score = float(hit.get("score", 0.0))
        if score >= threshold:
            return hit.get("id"), score
    return None, 0.0


# ─── Core Upload Logic ────────────────────────────────────────────────────────

def upload_note(
    *,
    client:        McpClient,
    project:       str,
    note_type:     str,
    title:         str,
    body:          str,
    extra_tags:    list[str],
    extra_links:   list[str],
    importance_override: int | None,
    force_update:  bool,
    source_file:   str | None,
) -> None:
    cfg = TYPE_CONFIG[note_type]

    # ── Build file_id: {project}/{type-folder}/{kebab-title}.md ────────────────
    file_id = f"{project}/{cfg['folder']}/{slugify(title)}.md"

    # ── Collect tags ──────────────────────────────────────────────────────────
    tags: list[str] = []
    tags.extend(cfg["base_tags"])
    tags.extend(extract_tags_from_body(body))
    tags.extend(extra_tags)
    tags = list(dict.fromkeys(t.lower().lstrip("#") for t in tags if t))

    # ── Collect wiki links ────────────────────────────────────────────────────
    wiki_links = list(dict.fromkeys(extract_wiki_links(body) + extra_links))

    # ── Enrich body for search ────────────────────────────────────────────────
    enriched_body = build_search_optimized_body(title, note_type, body, tags)

    # ── Metadata ──────────────────────────────────────────────────────────────
    metadata = build_metadata(note_type, source_file)

    # ── Importance ────────────────────────────────────────────────────────────
    importance = importance_override if importance_override is not None else cfg["importance"]

    # ── Duplicate check ───────────────────────────────────────────────────────
    existing_id, score = find_existing(client, title, DUP_THRESHOLD)

    if existing_id and not force_update:
        print(f"\n⚠️  Similar note already exists: '{existing_id}' (score={score:.2f})")
        print(    "   Use --force-update to overwrite it, or choose a more unique title.")
        sys.exit(1)

    if existing_id and force_update:
        print(f"\n🔄  Updating existing memory: {existing_id}")
        result = client.call("update_memory", {
            "file_id":    existing_id,
            "title":      title,
            "body":       enriched_body,
            "wiki_links": wiki_links,
            "tags":       tags,
            "metadata":   metadata,
        })
        raw = result if isinstance(result, str) else existing_id
        final_id = raw.removeprefix("Updated: ").split(" ")[0] if raw.startswith("Updated:") else existing_id
    else:
        print(f"\n✨  Creating new memory: {file_id}")
        result = client.call("create_memory", {
            "file_id":    file_id,
            "title":      title,
            "body":       enriched_body,
            "wiki_links": wiki_links,
            "tags":       tags,
            "metadata":   metadata,
        })
        raw = result if isinstance(result, str) else file_id
        final_id = raw.removeprefix("Created: ").split(" ")[0] if raw.startswith("Created:") else file_id

    print(f"   Server: {result}")

    # ── Set importance ────────────────────────────────────────────────────────
    imp_result = client.call("set_memory_importance", {
        "file_id":    final_id,
        "importance": importance,
    })
    print(f"   Importance [{importance}/10]: {imp_result}")

    # ── Summary ───────────────────────────────────────────────────────────────
    print(f"\n{'─'*60}")
    print(f"  ✅  Memory ready in MemoLink")
    print(f"  File ID   : {final_id}")
    print(f"  Project   : {project}")
    print(f"  Type      : {note_type}")
    print(f"  Tags      : {', '.join('#' + t for t in tags)}")
    print(f"  Wiki links: {', '.join(wiki_links) if wiki_links else '(auto-discovered by server)'}")
    print(f"  Importance: {importance}/10")
    print(f"{'─'*60}\n")


# ─── CLI ──────────────────────────────────────────────────────────────────────

def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        prog="vault_writer",
        description="Upload a skill, instruction, context or memory note to MemoLink.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    src = p.add_mutually_exclusive_group()
    src.add_argument("--file",  metavar="PATH", help="Path to a .md file to upload.")
    src.add_argument("--body",  metavar="TEXT", help="Inline markdown body content.")

    p.add_argument("--title",        metavar="TEXT",   help="Note title (auto-derived from H1 if omitted).")
    p.add_argument("--type",         metavar="TYPE",   choices=TYPE_CONFIG.keys(), required=True,
                   help="One of: skill | instruction | context | memory")
    p.add_argument("--project",      metavar="NAME",    default="",
                   help="Project name for vault namespacing (auto-detected from git if omitted).")
    p.add_argument("--tags",         metavar="a,b,c",  default="",
                   help="Comma-separated extra tags (no # prefix).")
    p.add_argument("--links",        metavar="a.md,b.md", default="",
                   help="Comma-separated extra wiki-link targets.")
    p.add_argument("--importance",   metavar="0-10",   type=int, default=None,
                   help="Override the default importance score for this type.")
    p.add_argument("--force-update", action="store_true",
                   help="Overwrite an existing similar note instead of aborting.")
    p.add_argument("--dry-run",      action="store_true",
                   help="Print what would be uploaded without calling MemoLink.")
    return p.parse_args()


def main() -> None:
    args = parse_args()

    # ── Load content ──────────────────────────────────────────────────────────
    source_file = None
    if args.file:
        path = Path(args.file)
        if not path.exists():
            sys.exit(f"❌  File not found: {args.file}")
        content     = path.read_text(encoding="utf-8")
        source_file = str(path)
    elif args.body:
        content = args.body
    else:
        sys.exit("❌  Provide either --file or --body.")

    # ── Derive title ──────────────────────────────────────────────────────────
    fallback_title = (
        Path(args.file).stem.replace("-", " ").replace("_", " ").title()
        if args.file else f"Untitled {args.type.capitalize()}"
    )
    title = args.title or derive_title(content, fallback_title)

    # ── Extra tags / links ────────────────────────────────────────────────────
    extra_tags  = [t.strip().lstrip("#") for t in args.tags.split(",")  if t.strip()]
    extra_links = [l.strip()             for l in args.links.split(",") if l.strip()]

    # ── Detect / validate project name ───────────────────────────────────────────────────
    project = slugify(args.project) if args.project else detect_project_name(
        cwd=str(Path(args.file).parent) if args.file else None
    )
    print(f"\n📂  Project : {project}")

    # ── Dry run ─────────────────────────────────────────────────────────────────
    if args.dry_run:
        cfg = TYPE_CONFIG[args.type]
        file_id = f"{project}/{cfg['folder']}/{slugify(title)}.md"
        tags    = list(dict.fromkeys(
            cfg["base_tags"] + extract_tags_from_body(content) + extra_tags
        ))
        wiki_links = list(dict.fromkeys(extract_wiki_links(content) + extra_links))
        print("\n📋  DRY RUN — nothing will be sent to MemoLink\n")
        print(f"  Project    : {project}")
        print(f"  Title      : {title}")
        print(f"  File ID    : {file_id}")
        print(f"  Type       : {args.type}")
        print(f"  Tags       : {', '.join('#' + t for t in tags)}")
        print(f"  Wiki links : {', '.join(wiki_links) or '(auto)'}")
        print(f"  Importance : {args.importance or cfg['importance']}/10")
        print(f"\n  Body preview (first 200 chars):\n  {content[:200].strip()}\n")
        return

    # ── Health check ──────────────────────────────────────────────────────────
    try:
        r = requests.get(
            f"{MEMOLINK_URL}/actuator/health",
            headers={"X-API-Key": MEMOLINK_KEY},
            timeout=5,
        )
        if r.status_code not in (200, 401):   # 401 means server IS up, just auth
            sys.exit(f"❌  MemoLink health check failed ({r.status_code}). Is the server running?")
        if r.status_code == 401:
            # Server is up but our key is wrong — let McpClient surface the error
            print(f"⚠️   Health check returned 401 — check MEMOLINK_KEY_VSCODE env var")
    except requests.ConnectionError:
        sys.exit(
            f"❌  Cannot reach MemoLink at {MEMOLINK_URL}.\n"
            "   Start it:  docker compose --profile compression up -d"
        )

    print(f"✅  MemoLink is healthy at {MEMOLINK_URL}")

    # ── Upload ────────────────────────────────────────────────────────────────
    client = McpClient(MCP_ENDPOINT, MEMOLINK_KEY)
    client.initialize()

    upload_note(
        client           = client,
        project          = project,
        note_type        = args.type,
        title            = title,
        body             = content,
        extra_tags       = extra_tags,
        extra_links      = extra_links,
        importance_override = args.importance,
        force_update     = args.force_update,
        source_file      = source_file,
    )


if __name__ == "__main__":
    main()
