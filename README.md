# AI HOA Support

AI HOA Support is a Spring Boot application that helps answer HOA policy questions by searching governing documents stored in a vector database. It loads PDF documents from a configured folder, extracts their text, chunks and embeds the content, and exposes the result through a Model Context Protocol (MCP) tool that an AI agent can call when answering resident or board-member questions.

The application is designed to support queries such as parking rules, pet policies, architectural standards, quiet-hours guidelines, fine enforcement, and document lookups based on the association's official governing documents.

## Architecture diagram

```mermaid
flowchart LR
    A[User / Agent] --> B[AI HOA Support MCP Server]
    B --> C[hoa-document-search Tool]
    C --> D[HoaSupportService]
    D --> E[(Chroma\nVector Database)]

    F[HOA PDF Docs Folder] --> G[HoaDocumentLoader]
    G --> H[PDF Text Extraction]
    H --> I[Text Chunking]
    I --> J[Ollama\nEmbedding Model]
    J -->|Embeddings| E

    A --> K[addDocuments Tool]
    K --> D
    D -->|Search queries| E

    subgraph App
        B
        C
        D
        E
        G
        H
        I
        J
        K
    end
```

## What this application accomplishes

- Reads HOA policy PDFs from a configurable folder
- Extracts document text using Spring AI + Tika
- Splits content into searchable chunks
- Generates embeddings with an Ollama embedding model
- Stores the vectors in Chroma vector database
- Exposes an MCP tool named `hoa-document-search`
- Lets an agent answer HOA questions grounded in actual governing documents rather than general knowledge

## MCP integration

This server exposes a `hoa-document-search` tool that can be used by an external AI agent or client to retrieve relevant HOA policy passages from the vector store.

The flow is:
- the agent queries the tool with a policy-related question
- the service searches Chroma for semantically relevant chunks
- the tool returns matching document excerpts
- the agent uses those excerpts to answer the user with document-backed guidance

## Technologies used

- Java 21
- Spring Boot 4.1.0
- Spring AI
- Spring AI MCP Server
- Ollama for embeddings
- Chroma vector database
- Apache Tika for PDF text extraction
- Maven

## Configuration

The application uses the following environment/config values:

```yaml
hoa:
  document-folder-path: ${HOA_DOCUMENT_FOLDER_PATH:}
  document-loading-enabled: ${HOA_DOCUMENT_LOADING_ENABLED:false}
```

Set the folder with HOA PDFs and enable document ingestion when needed.

## Sample prompts

Use prompts like the following in the agent:

- "What are the HOA rules for parking commercial vehicles?"
- "Are pets allowed in the community?"
- "What is the policy for architectural changes or exterior modifications?"
- "What are the rules around noise complaints and quiet hours?"
- "What does the CC&R say about leasing or rental restrictions?"
- "What are the fines and enforcement procedures for rule violations?"

## Project structure

```text
ai-hoa-support/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   ├── com/learning/
│   │   │   │   ├── init/
│   │   │   │   └── services/
│   │   └── resources/
│   │       └── application.yaml
├── hoa.agent.md
├── pom.xml
├── README.md
└── mvnw
```

## Summary

This project turns HOA governing documents into a searchable, agent-friendly knowledge layer. By combining document ingestion, embeddings, vector search, and MCP tooling, it gives an AI agent a reliable, document-backed way to answer HOA policy questions.
