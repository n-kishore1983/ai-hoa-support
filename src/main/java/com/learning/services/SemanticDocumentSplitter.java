package com.learning.services;


import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static org.springframework.ai.vectorstore.SimpleVectorStore.EmbeddingMath.cosineSimilarity;

@Service
public class SemanticDocumentSplitter {

    private final EmbeddingModel embeddingModel;

    public SemanticDocumentSplitter(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public List<String> split(Document document) {
        String[] sentences = document.getText().split("(?<=[.!?])\\s+");
        List<String> chunks = new ArrayList<>();;
        if(sentences.length == 0) {
            return chunks;
        }
       StringBuilder currentChunk = new StringBuilder(sentences[0]);
        for (int i = 1; i < sentences.length; i++) {
           float[] vectorA = embeddingModel.embed(sentences[i - 1]);
           float[] vectorB = embeddingModel.embed(sentences[i]);
            double similarity = cosineSimilarity(vectorA, vectorB);
            if(similarity < 0.72) {
                chunks.add(currentChunk.toString());
                currentChunk = new StringBuilder(sentences[i]);
            } else {
                currentChunk.append(" ").append(sentences[i]);
            }
        }
        if(!currentChunk.isEmpty()) {
            chunks.add(currentChunk.toString());
        }
        return chunks;
    }
}
