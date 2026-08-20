package com.learning.init;

import io.jchunk.semantic.Config;
import io.jchunk.semantic.SemanticChunker;
import io.jchunk.semantic.embedder.JChunkEmbedder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
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

        try (JChunkEmbedder embedder = new JChunkEmbedder()) {
            SemanticChunker semanticChunker = new SemanticChunker(embedder, Config.defaultConfig());

            try (Stream<Path> pathStream = Files.list(folderPath)) {
                List<Path> pdfFiles = pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .toList();

                for (Path path : pdfFiles) {
                    FileSystemResource pdfResource = new FileSystemResource(path);
                    // 1. Read PDF (one Document per page by default)
                    PagePdfDocumentReader documentReader = new PagePdfDocumentReader(pdfResource, PdfDocumentReaderConfig.builder()
                            .withPagesPerDocument(1) // 1 page = 1 Document
                            .withPageExtractedTextFormatter(
                                    ExtractedTextFormatter.builder()
                                            .withNumberOfTopTextLinesToDelete(1)    // remove headers
                                            .withNumberOfBottomTextLinesToDelete(1) // remove footers
                                            .build())
                            .build());
                    List<Document> pages = documentReader.get();
                //  2. Add useful metadata
                    pages.forEach(doc -> {
                        doc.getMetadata().put("source", pdfResource.getFilename());
                        doc.getMetadata().put("type", "pdf");
                    });
                    // 3. Chunk each page text into safe segments and add to the vector store immediately
                    TokenTextSplitter tokenTextSplitter = TokenTextSplitter.builder()
                            .withChunkSize(500)
                            .withMinChunkSizeChars(100)
                            .withMinChunkLengthToEmbed(5)
                            .withMaxNumChunks(10000)
                            .withKeepSeparator(true)
                            .build();
                    List<Document> chunks = tokenTextSplitter.apply(pages);
                    vectorStore.add(chunks);
                    LOGGER.info(String.format("Ingested %d chunks from %s", chunks.size(), pdfResource.getFilename()));
                }
            }
        }
        catch (Exception exception) {
            throw new IllegalStateException("Failed to read HOA PDF files from: " + documentFolderPath, exception);
        }
    }

}
