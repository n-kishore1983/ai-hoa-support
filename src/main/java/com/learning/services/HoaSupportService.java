package com.learning.services;

import com.learning.domain.HoaDocumentPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.ai.mcp.annotation.McpTool;

import java.util.List;

@Service
public class HoaSupportService {

    private static final Logger log = LoggerFactory.getLogger(HoaSupportService.class);

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public HoaSupportService(VectorStore vectorStore, ChatModel chatModel) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
    }

    @McpTool(name = "hoa-document-search", description = "Provides support information for HOA-related queries")
    public String getHoaSupportInfo(String query) {
        try {
            log.info("HOA search request: {}", query);

            SearchRequest searchRequest = SearchRequest.builder()
                    .topK(10)                        // Limit to top 10 matches
                    .similarityThreshold(0.3)
                    .query(query)// Minimum similarity score
                    .build();
            log.debug("HOA vector search request: {}", searchRequest);

            ChatClient chatClient = ChatClient.builder(chatModel)
                    .build();
            return chatClient.prompt().advisors(
                    QuestionAnswerAdvisor.builder(vectorStore)
                            .searchRequest(searchRequest)
                            .build()
            ).user(query).call().content();
        } catch (Exception e) {
            log.error("Error processing HOA search request for query: {}", query, e);
            return "I could not process that HOA question right now. Please try again later.";
        }
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
