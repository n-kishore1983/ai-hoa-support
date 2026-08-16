package com.learning.services;

import com.learning.domain.HoaDocumentPayload;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.ai.mcp.annotation.McpTool;

import java.util.List;

@Service
public class HoaSupportService {

    private VectorStore vectorStore;

    @Autowired
    public HoaSupportService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @McpTool(name = "hoa-document-search", description = "Provides support information for HOA-related queries")
    public String getHoaSupportInfo(String query) {
        List<Document> results = this.vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(10)                        // Limit to top 10 matches
                        .similarityThreshold(0.0)       // Minimum similarity score
                        .build()
        );
        // Process the results and return the relevant information
        StringBuilder sb = new StringBuilder();
        for (Document doc : results) {
            sb.append(doc.getFormattedContent()).append("\n");

            System.out.println("score=" + doc.getScore());
            assert doc.getText() != null;
            System.out.println(doc.getText().substring(0, Math.min(200, doc.getText().length())));
            System.out.println("---");
        }
        return sb.toString();
    }

    @McpTool(name = "hoa-document-add", description = "Adds a new document to the HOA store")
    public String addDocumentToVectorStore(HoaDocumentPayload hoaDocumentPayload) {
        Document document = Document.builder()
                .text(hoaDocumentPayload.content())
                .metadata("title", hoaDocumentPayload.title())
                .metadata("mimeType", hoaDocumentPayload.mimeType())
                .build();
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(800)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(50)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build();
        List<Document> splitDocuments = splitter.apply(List.of(document));
        if(!splitDocuments.isEmpty()) {
            vectorStore.add(splitDocuments);
            return "Document added to vector store successfully.";
        } else {
            return "Document could not be added to vector store. No valid chunks were created.";
        }
    }
}
