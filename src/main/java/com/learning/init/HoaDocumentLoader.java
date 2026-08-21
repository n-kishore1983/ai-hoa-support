package com.learning.init;

import com.learning.services.SemanticDocumentSplitter;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Component
public class HoaDocumentLoader {

    private static final Logger LOGGER = Logger.getLogger(HoaDocumentLoader.class.getName());

    private final SemanticDocumentSplitter semanticDocumentSplitter;
    private final VectorStore vectorStore;
    private final String documentFolderPath;
    private final boolean documentLoadingEnabled;

    public HoaDocumentLoader(SemanticDocumentSplitter semanticDocumentSplitter, VectorStore vectorStore,
                             @Value("${hoa.document-folder-path:}") String documentFolderPath,
                             @Value("${hoa.document-loading-enabled:false}") boolean documentLoadingEnabled) {
        this.semanticDocumentSplitter = semanticDocumentSplitter;
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

        try (Stream<Path> pathStream = Files.list(folderPath)) {
            List<Path> pdfFiles = pathStream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                .toList();

            for (Path path : pdfFiles) {
                FileSystemResource pdfResource = new FileSystemResource(path);
                PagePdfDocumentReader documentReader = new PagePdfDocumentReader(pdfResource, PdfDocumentReaderConfig.builder()
                        .withPagesPerDocument(1)
                        .withPageExtractedTextFormatter(
                                ExtractedTextFormatter.builder()
                                        .withNumberOfTopTextLinesToDelete(1)
                                        .withNumberOfBottomTextLinesToDelete(1)
                                        .build())
                        .build());

                List<Document> pages = documentReader.get();

                for (Document page : pages) {
                    page.getMetadata().put("source", pdfResource.getFilename());
                    page.getMetadata().put("type", "pdf");
                    String text = page.getText();
                    if (!StringUtils.hasText(text)) {
                        continue;
                    }

                    Map<String, Object> cleanMetadata = new java.util.HashMap<>(page.getMetadata());
                    List<String> chunks = semanticDocumentSplitter.split(
                        new Document(text, cleanMetadata));

                    List<Document> splitDocuments = new java.util.ArrayList<>();
                    for (String chunk : chunks) {
                        if (StringUtils.hasText(chunk)) {
                            splitDocuments.add(new Document(chunk, cleanMetadata));
                        }
                    }

                    if (!splitDocuments.isEmpty()) {
                        vectorStore.add(splitDocuments);
                        LOGGER.info(String.format("Ingested %d chunks from %s", splitDocuments.size(), pdfResource.getFilename()));
                    }
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read HOA PDF files from: " + documentFolderPath, exception);
        }
    }

}
