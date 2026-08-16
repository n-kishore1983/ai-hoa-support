package com.learning.init;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
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

        List<Document> documents = new ArrayList<>();
        try (Stream<Path> pathStream = Files.list(folderPath)) {
            pathStream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                .forEach(path -> {
                    PagePdfDocumentReader documentReader = new PagePdfDocumentReader(new FileSystemResource(path));
                    List<Document> rawDocuments = documentReader.get();
                    TokenTextSplitter splitter = TokenTextSplitter.builder()
                            .withChunkSize(800)
                            .withMinChunkSizeChars(350)
                            .withMinChunkLengthToEmbed(50)
                            .withMaxNumChunks(10000)
                            .withKeepSeparator(true)
                            .build();
                    List<Document> splitDocuments = splitter.apply(rawDocuments);
                    documents.addAll(splitDocuments);
                });
        }
        catch (IOException exception) {
            throw new IllegalStateException("Failed to read HOA PDF files from: " + documentFolderPath, exception);
        }

        if (!documents.isEmpty()) {
            vectorStore.add(documents);
            LOGGER.info("Successfully ingested " + documents.size() + " HOA documents into the vector store.");
        }
    }
}
