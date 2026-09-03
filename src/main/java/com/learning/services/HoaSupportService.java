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
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.util.StringUtils;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class HoaSupportService {


    private final SemanticDocumentSplitter semanticDocumentSplitter;
    private final PdfOcrDetector pdfOcrDetector;
    private final VectorStore vectorStore;
    private final LucerneSearch lucerneSearch;
    private final LucerneDocumentWriter lucerneDocumentWriter;
    private final ChatModel chatModel;
    private final CallAdvisor tokenUsageAdvisor;
    private final RedisTemplate<String, String> redisTemplate;
    private final boolean ocrEnabled;
    private final String ocrLanguage;
    private final String tesseractPath;
    private final String tessdataPath;

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger(HoaSupportService.class.getName());

    public HoaSupportService(SemanticDocumentSplitter semanticDocumentSplitter,
                             PdfOcrDetector pdfOcrDetector,
                             VectorStore vectorStore, LucerneSearch lucerneSearch, LucerneDocumentWriter lucerneDocumentWriter,
                             ChatModel chatModel, CallAdvisor tokenUsageAdvisor,
                             RedisTemplate<String, String> redisTemplate,
                             @Value("${hoa.ocr-enabled:true}") boolean ocrEnabled,
                             @Value("${hoa.ocr-language:eng}") String ocrLanguage,
                             @Value("${hoa.ocr-tesseract-path:}") String tesseractPath,
                             @Value("${hoa.ocr-tessdata-path:}") String tessdataPath) {
        this.semanticDocumentSplitter = semanticDocumentSplitter;
        this.lucerneSearch = lucerneSearch;
        this.pdfOcrDetector = pdfOcrDetector;
        this.vectorStore = vectorStore;
        this.lucerneDocumentWriter = lucerneDocumentWriter;
        this.chatModel = chatModel;
        this.tokenUsageAdvisor = tokenUsageAdvisor;
        this.redisTemplate = redisTemplate;
        this.ocrEnabled = ocrEnabled;
        this.ocrLanguage = ocrLanguage;
        this.tesseractPath = tesseractPath;
        this.tessdataPath = tessdataPath;
    }

    @McpTool(name = "hoa-document-search", description = "Provides support information for HOA-related queries")
    public String getHoaSupportInfo(String query) {
        try {
            LOGGER.info("HOA search request: " + query);

            SearchRequest searchRequest = SearchRequest.builder()
                    .topK(10)                        // Limit to top 10 matches
                    .similarityThreshold(0.3)
                    .query(query)// Minimum similarity score
                    .build();
            LOGGER.fine("HOA vector search request: " + searchRequest);
            var vectorSearchResults = vectorStore.similaritySearch(searchRequest);
            LOGGER.fine("HOA vector search results: " + vectorSearchResults);
            var luceneSearchResults = lucerneSearch.search(query, 10);
            LOGGER.fine("HOA Lucene search results: " + luceneSearchResults);
            var fusedResults = reciprocalRankFusion(vectorSearchResults, luceneSearchResults, 10);
            LOGGER.fine("HOA fused search results: " + fusedResults);
            String context = fusedResults.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n"));
            String template = """
            Use the following context to answer the question.
            
            Context:
            {context}
       
            """;
            PromptTemplate promptTemplate = new PromptTemplate(template);
            Map<String, Object> modelMap = Map.of("context", context);
            ChatClient chatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(List.of(tokenUsageAdvisor))
                    .build();
            ChatResponse chatResponse = chatClient.prompt(promptTemplate.create(modelMap)).user(query).call().chatResponse();

            return chatResponse.getResult().getOutput().getText();
        } catch (Exception e) {
            LOGGER.severe("Error processing HOA search request for query: " + query + ". " + e.getMessage());
            return "I could not process that HOA question right now. Please try again later.";
        }
    }

    @McpTool(name = "hoa-document-add", description = "Adds a new document to the HOA store")
    public String addDocumentToVectorStore(String hoaDocumentPathStr) throws IOException {
        Path path1 = Path.of(hoaDocumentPathStr);
        if (!Files.isRegularFile(path1)) {
            return "Document path is invalid or the file does not exist.";
        }
        String checksum = com.google.common.io.Files.asByteSource(path1.toFile()).hash(com.google.common.hash.Hashing.sha256()).toString();
        LOGGER.info("Checksum for file " + path1.getFileName() + ": " + checksum);
        if(redisTemplate.hasKey(checksum)) {
            LOGGER.info("Skipping HOA PDF file (already processed): " + path1.getFileName());
            return "Document has already been processed.";
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
            try {
                totalChunksForFile += splitAndStore(text, cleanMetadata);
            } catch (IOException e) {
                LOGGER.severe("Error splitting and storing document chunk for document " + path1.getFileName() + ": " + e.getMessage());
            }
        }

        try {
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
        } catch (IOException e) {
            LOGGER.severe("Error processing OCR content for document " + path1.getFileName() + ": " + e.getMessage());
        }
        if (totalChunksForFile > 0) {
            LOGGER.info("Ingested " + totalChunksForFile + " chunks for document " + path1.getFileName());
            redisTemplate.opsForValue().set(checksum, path1.getFileName().toString());
            return "Document added to vector store successfully.";
        }

        return "Document could not be added to vector store. No valid chunks were created.";
    }

    public int splitAndStore(String text, Map<String, Object> metadata) throws IOException {
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
        lucerneDocumentWriter.add(splitDocuments);
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
            LOGGER.severe("OCR extraction failed for " + pdfPath.getFileName() + ": " + exception.getMessage());
            return "";
        }
    }

    private List<Document> reciprocalRankFusion(
            List<Document> vectorResults,
            List<Document> bm25Results,
            int limit) {

        Map<String, Double> scores = new HashMap<>();
        Map<String, Document> documents = new HashMap<>();

        addRrfScores(vectorResults, scores, documents);
        addRrfScores(bm25Results, scores, documents);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> documents.get(entry.getKey()))
                .toList();
    }

    private void addRrfScores(
            List<Document> results,
            Map<String, Double> scores,
            Map<String, Document> documents) {

        final int k = 60;

        for (int i = 0; i < results.size(); i++) {
            var document = results.get(i);
            var rank = i + 1;

            documents.putIfAbsent(document.getId(), document);

            scores.merge(
                    document.getId(),
                    1.0 / (k + rank),
                    Double::sum);
        }
    }

}
