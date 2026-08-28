package com.learning.init;

import com.learning.services.HoaSupportService;
import com.learning.services.PdfOcrDetector;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Component
public class HoaDocumentLoader {

    private static final Logger LOGGER = Logger.getLogger(HoaDocumentLoader.class.getName());

    private final HoaSupportService hoaSupportService;
    private final PdfOcrDetector pdfOcrDetector;
    private final RedisTemplate<String, String> redisTemplate;
    private final String documentFolderPath;
    private final boolean documentLoadingEnabled;
    private final boolean ocrEnabled;


    public HoaDocumentLoader(HoaSupportService hoaSupportService,
                             PdfOcrDetector pdfOcrDetector,
                             RedisTemplate<String, String> redisTemplate,
                             @Value("${hoa.document-folder-path:}") String documentFolderPath,
                             @Value("${hoa.document-loading-enabled:false}") boolean documentLoadingEnabled,
                             @Value("${hoa.ocr-enabled:true}") boolean ocrEnabled) {
        this.hoaSupportService = hoaSupportService;
        this.pdfOcrDetector = pdfOcrDetector;
        this.redisTemplate = redisTemplate;
        this.documentFolderPath = documentFolderPath;
        this.documentLoadingEnabled = documentLoadingEnabled;
        this.ocrEnabled = ocrEnabled;
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
                String checksum = com.google.common.io.Files.asByteSource(path.toFile()).hash(com.google.common.hash.Hashing.sha256()).toString();
                LOGGER.info("Checksum for file " + path.getFileName() + ": " + checksum);
                if(redisTemplate.hasKey(checksum)) {
                    LOGGER.info("Skipping HOA PDF file (already processed): " + path.getFileName());
                    continue;
                }
                redisTemplate.opsForValue().set(checksum, path.getFileName().toString());
                LOGGER.info("Processing HOA PDF file: " + path.getFileName());
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
                int totalChunksForFile = 0;
                for (Document page : pages) {
                    page.getMetadata().put("source", pdfResource.getFilename());
                    page.getMetadata().put("type", "pdf");
                    String text = page.getText();

                    Map<String, Object> cleanMetadata = new HashMap<>(page.getMetadata());
                    totalChunksForFile += hoaSupportService.splitAndStore(text, cleanMetadata);
                }

                if (ocrEnabled && pdfOcrDetector.hasOcrContent(path)) {
                    String ocrText = hoaSupportService.extractTextWithOcr(path);
                    LOGGER.info("ocrText: " + ocrText);
                    if (StringUtils.hasText(ocrText)) {
                        Map<String, Object> ocrMetadata = new HashMap<>();
                        ocrMetadata.put("source", pdfResource.getFilename());
                        ocrMetadata.put("type", "pdf-ocr");
                        ocrMetadata.put("ocr", true);
                        totalChunksForFile += hoaSupportService.splitAndStore(ocrText, ocrMetadata);
                        LOGGER.info(String.format("OCR fallback used for %s", pdfResource.getFilename()));
                    } else {
                        LOGGER.warning(String.format("No text extracted for %s, even after OCR fallback.", pdfResource.getFilename()));
                    }
                }

                if (totalChunksForFile > 0) {
                    LOGGER.info(String.format("Ingested %d chunks from %s", totalChunksForFile, pdfResource.getFilename()));
                }
                LOGGER.info(String.format("Finished processing HOA PDF file: %s", path.getFileName()));
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read HOA PDF files from: " + documentFolderPath, exception);
        }
    }

}


