package com.learning.init;

import io.jchunk.core.chunk.Chunk;
import io.jchunk.semantic.Config;
import io.jchunk.semantic.SemanticChunker;
import io.jchunk.semantic.embedder.JChunkEmbedder;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Component
public class HoaDocumentLoader {

    private static final Logger LOGGER = Logger.getLogger(HoaDocumentLoader.class.getName());

    private final VectorStore vectorStore;
    private final String documentFolderPath;
    private final boolean documentLoadingEnabled;

    public HoaDocumentLoader(VectorStore vectorStore,
                             @Value("${hoa.document-folder-path:}") String documentFolderPath,
                             @Value("${hoa.document-loading-enabled:false}") boolean documentLoadingEnabled) {
        this.vectorStore = vectorStore;
        this.documentFolderPath = documentFolderPath;
        this.documentLoadingEnabled = documentLoadingEnabled;
    }

    @PostConstruct
    public void init() {
        if (!documentLoadingEnabled) {
            LOGGER.info("Skipping HOA document ingestion because 'hoa.document-loading-enabled' is false.");
            return;
        }

        if (!StringUtils.hasText(documentFolderPath)) {
            LOGGER.warning("Skipping HOA document ingestion because 'hoa.document-folder-path' is not configured.");
            return;
        }

        Path folderPath = Path.of(documentFolderPath);
        if (!Files.isDirectory(folderPath)) {
            throw new IllegalStateException("HOA document folder path is invalid: " + documentFolderPath);
        }

        try (JChunkEmbedder embedder = new JChunkEmbedder()) {
            SemanticChunker semanticChunker = new SemanticChunker(embedder, Config.defaultConfig());

            try (Stream<Path> pathStream = Files.list(folderPath)) {
                List<Path> pdfFiles = pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .toList();

                for (Path path : pdfFiles) {
                    PagePdfDocumentReader documentReader = new PagePdfDocumentReader(new FileSystemResource(path));
                    List<Document> pdfDocuments = documentReader.get();

                    for (Document document : pdfDocuments) {
                        String pageText = document.getText();
                        if (!StringUtils.hasText(pageText)) {
                            continue;
                        }

                        for (String safeSegment : splitTextIntoSafeSegments(pageText)) {
                            try {
                                List<Chunk> chunks = semanticChunker.split(safeSegment);
                                List<Document> splitDocuments = new ArrayList<>();
                                for (Chunk chunk : chunks) {
                                    splitDocuments.add(new Document(chunk.content(), Map.of("source", path.getFileName().toString())));
                                }

                                if (!splitDocuments.isEmpty()) {
                                    vectorStore.add(splitDocuments);
                                    LOGGER.info("Successfully ingested " + splitDocuments.size() + " chunks from " + path.getFileName());
                                }
                            }
                            catch (Exception exception) {
                                LOGGER.warning("Skipping oversized text segment from " + path.getFileName() + ": " + exception.getMessage());
                            }
                        }
                    }
                }
            }
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to read HOA PDF files from: " + documentFolderPath, exception);
        }
    }

    private List<String> splitTextIntoSafeSegments(String text) {
        List<String> segments = new ArrayList<>();
        String[] paragraphs = text.split("\\r?\\n\\s*\\r?\\n+");
        StringBuilder current = new StringBuilder();
        final int maxCharsPerSegment = 1800;

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (!StringUtils.hasText(trimmed)) {
                continue;
            }

            if (current.length() + trimmed.length() + 2 <= maxCharsPerSegment) {
                if (current.length() > 0) {
                    current.append(System.lineSeparator());
                }
                current.append(trimmed);
            }
            else {
                if (current.length() > 0) {
                    segments.add(current.toString());
                    current = new StringBuilder();
                }
                if (trimmed.length() > maxCharsPerSegment) {
                    for (String part : splitLongParagraph(trimmed, maxCharsPerSegment)) {
                        segments.add(part);
                    }
                }
                else {
                    current.append(trimmed);
                }
            }
        }

        if (current.length() > 0) {
            segments.add(current.toString());
        }

        return segments.isEmpty() ? List.of(text) : segments;
    }

    private List<String> splitLongParagraph(String paragraph, int maxChars) {
        List<String> parts = new ArrayList<>();
        for (int start = 0; start < paragraph.length(); start += maxChars) {
            int end = Math.min(start + maxChars, paragraph.length());
            parts.add(paragraph.substring(start, end).trim());
        }
        return parts;
    }
}
