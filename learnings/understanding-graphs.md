# Understanding Graphs in Java (for MemoLink)

Since you are new to graph data structures, this guide will explain the fundamental concepts of Graphs and how they are practically applied in the `memolink-core` library.

## What is a Graph?

In computer science, a **Graph** is a data structure used to represent connections between different entities. It consists of two main parts:
1. **Nodes (or Vertices)**: The entities themselves.
2. **Edges (or Links)**: The connections between those entities.

A classic example of a graph is a social network: 
- The **Nodes** are people (Alice, Bob, Charlie).
- The **Edges** are their friendships (Alice is friends with Bob).

### Directed vs. Undirected Edges
- **Directed Edge**: A one-way connection (e.g., Twitter followers. Alice follows Bob, but Bob might not follow Alice).
- **Undirected Edge**: A two-way connection (e.g., Facebook friends. If Alice is friends with Bob, Bob is friends with Alice).

### Weighted Edges
Sometimes, connections have different strengths or costs. This is called a **Weight**. 
- E.g., on a map app, the weight of an edge between two cities could be the driving distance. In a social network, weight could represent how often two people interact.

---

## How MemoLink Uses Graphs

In `memolink-core`, the graph is the heart of the system. It connects markdown notes so users can discover related thoughts and ideas.

### 1. Nodes = Markdown Files
In MemoLink, every `.md` file is a **Node**. 
The Java class representing a parsed file is `MdFileMetadata` (and simplified as `GraphNode` for the UI).
- Node ID: `spring-boot.md`
- Node Title: "Spring Boot Basics"

### 2. Edges = Relationships
An **Edge** in MemoLink means two notes are related. The Java class is `GraphEdge`. 
Edges are created by the `RelationshipEngine` and are **Bidirectional** (if Note A is related to Note B, Note B is related to Note A) and **Weighted**.

MemoLink calculates weights based on how strongly related the notes are:
- **Wiki Link (`[[Spring Boot]]`)**: Strongest relationship (Weight +5)
- **Shared Tags (`#java`)**: Medium relationship (Weight +2 per shared tag)
- **Shared Keywords ("database", "api")**: Weak relationship (Weight +1 per shared keyword)

If `java-basics.md` and `spring-boot.md` share the tag `#java` and have a wiki-link between them, their edge weight might be `7`. 

### 3. The Adjacency List (How it is stored in Java)
To store a graph in Java, we don't just put nodes and edges in a random list. We need a way to quickly ask: *"What are all the notes connected to `spring-boot.md`?"*

The standard way to do this is an **Adjacency List**. In `KnowledgeGraph.java`, it looks like this:

```java
// Maps a Node ID (String) to a List of connected Edges
private final Map<String, List<GraphEdge>> adjacency;
```

When you look up `spring-boot.md` in this `Map`, you instantly get a `List<GraphEdge>` containing all the relationships to other notes.

### 4. Graph Traversal (Breadth-First Search)
Often, you want to explore the graph. For example, *"Find all notes within 2 degrees of separation from `java-basics.md`."*

To do this, MemoLink uses the `GraphTraversalService`, which implements a **Breadth-First Search (BFS)** algorithm. 

**How BFS works in Java:**
1. You start at the root node (`java-basics.md`) and put it in a `Queue`.
2. You keep a `Set<String> visited` to remember which notes you've already seen (so you don't get stuck in an infinite loop if A connects to B, and B connects back to A).
3. You pull a node from the Queue, look up its neighbors in the `adjacency` map, and add those unvisited neighbors to the back of the Queue.
4. You repeat this until you've reached your desired depth.

## Summary

As a Java developer, you can think of a Graph simply as a `Map` where the **Keys** are your objects (Notes), and the **Values** are `Lists` containing the objects they are connected to. 

Everything else in `memolink-core` (parsing Markdown, extracting tags, generating AI embeddings) is just the preparation work required to figure out exactly how to build that `Map`!
