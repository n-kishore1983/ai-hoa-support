package com.learning.services;

import com.learning.init.HoaDocumentLoader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

@Service
public class PdfOcrDetector {

    private static final Logger LOGGER = Logger.getLogger(PdfOcrDetector.class.getName());

    public boolean hasOcrContent(Path pdfPath) {
        LOGGER.info("Checking if PDF has OCR content: " + pdfPath);
        if (pdfPath == null || !Files.isRegularFile(pdfPath)) {
            return false;
        }

        try (PDDocument pdDocument = Loader.loadPDF(pdfPath.toFile())) {
            String extractedText = new PDFTextStripper().getText(pdDocument);
            boolean hasSelectableText = extractedText != null && !extractedText.trim().isEmpty();
            boolean hasImageContent = containsImageContent(pdDocument);

            LOGGER.info(String.format("PDF %s hasSelectableText=%b, hasImageContent=%b", pdfPath.getFileName(), hasSelectableText, hasImageContent));
            return hasImageContent;
        } catch (IOException exception) {
            LOGGER.warning(String.format("Failed to check OCR content for %s: %s", pdfPath.getFileName(), exception.getMessage()));
            return false;
        }
    }

    public boolean hasOcrContent(Document document) {
        if (document == null) {
            return false;
        }

        Object ocrFlag = document.getMetadata().get("ocr");
        if (Boolean.TRUE.equals(ocrFlag)) {
            return true;
        }

        Object type = document.getMetadata().get("type");
        return "pdf-ocr".equalsIgnoreCase(String.valueOf(type));
    }

    private boolean containsImageContent(PDDocument pdDocument) throws IOException {
        for (PDPage page : pdDocument.getPages()) {
            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }
            for (var xObjectName : resources.getXObjectNames()) {
                PDXObject xObject = resources.getXObject(xObjectName);
                if (xObject instanceof PDImageXObject) {
                    return true;
                }
            }
        }
        return false;
    }
}
