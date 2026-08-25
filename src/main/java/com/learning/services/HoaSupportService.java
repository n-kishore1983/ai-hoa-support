package com.learning.services;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.parser.ocr.TesseractOCRParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.util.StringUtils;
import org.xml.sax.SAXException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class HoaSupportService {

    private static final Logger log = LoggerFactory.getLogger(HoaSupportService.class);

    private final SemanticDocumentSplitter semanticDocumentSplitter;
    private final PdfOcrDetector pdfOcrDetector;
    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final boolean ocrEnabled;
    private final String ocrLanguage;
    private final String tesseractPath;
    private final String tessdataPath;

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(HoaSupportService.class.getName());

    public HoaSupportService(SemanticDocumentSplitter semanticDocumentSplitter, PdfOcrDetector pdfOcrDetector, VectorStore vectorStore, ChatModel chatModel,
                             @Value("${hoa.ocr-enabled:true}") boolean ocrEnabled,
                             @Value("${hoa.ocr-language:eng}") String ocrLanguage,
                             @Value("${hoa.ocr-tesseract-path:}") String tesseractPath,
                             @Value("${hoa.ocr-tessdata-path:}") String tessdataPath) {
        this.semanticDocumentSplitter = semanticDocumentSplitter;
        this.pdfOcrDetector = pdfOcrDetector;
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.ocrEnabled = ocrEnabled;
        this.ocrLanguage = ocrLanguage;
        this.tesseractPath = tesseractPath;
        this.tessdataPath = tessdataPath;
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
    public String addDocumentToVectorStore(String hoaDocumentPathStr) {
        Path path1 = Path.of(hoaDocumentPathStr);
        if (!Files.isRegularFile(path1)) {
            return "Document path is invalid or the file does not exist.";
        }
        LOGGER.info("Processing HOA PDF file: " + path1.getFileName());

        FileSystemResource pdfResource = new FileSystemResource(path1);
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
            totalChunksForFile += splitAndStore(text, cleanMetadata);
        }

        if(ocrEnabled && pdfOcrDetector.hasOcrContent(path1)) {
            String ocrText = extractTextWithOcr(path1);
            if (StringUtils.hasText(ocrText)) {
                Map<String, Object> ocrMetadata = new HashMap<>();
                ocrMetadata.put("source", pdfResource.getFilename());
                ocrMetadata.put("type", "pdf-ocr");
                ocrMetadata.put("ocr", true);
                totalChunksForFile += splitAndStore(ocrText, ocrMetadata);
            }
        }
        if (totalChunksForFile > 0) {
            log.info("Ingested {} chunks for document {}", totalChunksForFile, path1.getFileName());
            return "Document added to vector store successfully.";
        }

        return "Document could not be added to vector store. No valid chunks were created.";
    }

    public int splitAndStore(String text, Map<String, Object> metadata) {
        List<String> chunks = semanticDocumentSplitter.split(new Document(text, metadata));
        List<Document> splitDocuments = new ArrayList<>();
        for (String chunk : chunks) {
            if (StringUtils.hasText(chunk)) {
                splitDocuments.add(new Document(chunk, metadata));
            }
        }

        if (splitDocuments.isEmpty()) {
            return 0;
        }

        vectorStore.add(splitDocuments);
        return splitDocuments.size();
    }

    public String extractTextWithOcr(Path pdfPath) {
        if (pdfPath == null) {
            return "";
        }

        PDFParserConfig pdfParserConfig = new PDFParserConfig();
        pdfParserConfig.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.OCR_ONLY);
        pdfParserConfig.setExtractInlineImages(true);
        pdfParserConfig.setExtractUniqueInlineImagesOnly(true);
        pdfParserConfig.setOcrDPI(300);

        TesseractOCRConfig ocrConfig = new TesseractOCRConfig();
        ocrConfig.setLanguage(ocrLanguage);
        ocrConfig.setSkipOcr(false);
        ocrConfig.setTimeoutSeconds(300);

        TesseractOCRParser ocrParser = new TesseractOCRParser();
        if (StringUtils.hasText(tesseractPath)) {
            ocrParser.setTesseractPath(tesseractPath);
        }
        if (StringUtils.hasText(tessdataPath)) {
            ocrParser.setTessdataPath(tessdataPath);
        }

        ParseContext parseContext = new ParseContext();
        parseContext.set(PDFParserConfig.class, pdfParserConfig);
        parseContext.set(TesseractOCRConfig.class, ocrConfig);
        parseContext.set(TesseractOCRParser.class, ocrParser);

        try (var inputStream = Files.newInputStream(pdfPath)) {
            BodyContentHandler handler = new BodyContentHandler(-1);
            new AutoDetectParser().parse(inputStream, handler, new Metadata(), parseContext);
            return handler.toString();
        } catch (TikaException | SAXException | java.io.IOException exception) {
            log.info(String.format("OCR extraction failed for %s: %s", pdfPath.getFileName(), exception.getMessage()));
            return "";
        }
    }

}
