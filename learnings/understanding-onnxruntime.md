# Understanding ONNX Runtime in MemoLink

If you look at the dependencies for `memolink-core`, you'll see `com.microsoft.onnxruntime`. This is one of the most important pieces of the puzzle for enabling MemoLink's AI capabilities. 

Here is a breakdown of what it is, why it was chosen, and exactly how it is used in the `EmbeddingService`.

## What is ONNX Runtime?
**ONNX (Open Neural Network Exchange)** is an open standard format for machine learning models. It allows models trained in Python frameworks (like PyTorch or TensorFlow) to be exported into a universal `.onnx` file.

**ONNX Runtime** is the high-performance inference engine built by Microsoft that runs these `.onnx` models. It has bindings for many languages, including Java. 

In `memolink-core`, ONNX Runtime is used to run a **Sentence Transformer** model (specifically `all-MiniLM-L6-v2`) to generate 384-dimensional vector embeddings for your Markdown notes.

## Why use ONNX in Java?
You might wonder: *Why not just make an API call to OpenAI, or run a Python microservice?*

1. **100% Offline & Private**: By running the model locally, your personal notes never leave your machine.
2. **Zero Network Latency**: Generating embeddings takes milliseconds because there are no HTTP requests.
3. **No Python Required**: A common headache with AI in Java is trying to bundle Python or heavy Deep Java Library (DJL) engines. By using the raw `ai.onnxruntime` Java API alongside a custom pure-Java `BertTokenizer`, the entire AI engine bundles perfectly into a standard Spring Boot "fat jar". The user doesn't need to install PyTorch or manage Python environments.

## How it works in `EmbeddingService.java`

If you look at the code, generating an embedding involves a few distinct steps:

### 1. Asynchronous Loading
AI models can be large (MiniLM is ~90MB). Loading it blocks the thread. 
`EmbeddingService` loads the model into an `OrtSession` using a background thread (`CompletableFuture.runAsync`). Until it finishes loading, the app falls back to pure BM25 keyword search.

### 2. Tokenization (Text → Numbers)
Neural networks can't read text; they read numbers. 
Before hitting ONNX, the text is passed to `BertTokenizer`. This class reads a `tokenizer.json` file and converts `"Hello World"` into three distinct arrays of numbers:
- `input_ids`: The vocabulary IDs of the words.
- `attention_mask`: Tells the model which tokens are real words vs empty padding.
- `token_type_ids`: Used to distinguish sentences (usually just 0s for this use case).

### 3. Inference (ONNX Runtime)
These three arrays are converted into ONNX memory structures called `OnnxTensor`s. 
We pass them to the model by calling `ortSession.run(inputs)`. 

### 4. Mean-Pooling & Normalization
The model outputs a massive tensor containing vectors for *every single word*. 
We don't want a vector for every word; we want a single vector representing the *entire sentence*. 

- **Mean-Pooling**: The Java code averages out all the word vectors (ignoring padding) to create a single 384-dimensional `float[]`. (If the model was pre-configured for sentence-pooling, it just grabs the `sentence_embedding` output directly).
- **L2-Normalization**: Finally, the vector is normalized (its magnitude is scaled to 1.0). This is a crucial math step that allows Lucene to use "Cosine Similarity" to instantly find matching vectors later on!

## Summary
By leveraging `com.microsoft.onnxruntime`, MemoLink achieves native, offline, lightning-fast semantic search directly inside the JVM, completely avoiding the complexity of Python or third-party API costs!
