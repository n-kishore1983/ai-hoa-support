package com.learning.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public class LucerneDocumentWriter  implements  AutoCloseable{
    private static final String ID_FIELD = "id";
    private static final String CONTENT_FIELD = "content";
    private static final String METADATA_FIELD = "metadata";

    private final Analyzer analyzer;
    private final Directory directory;
    private final IndexWriter writer;
    private final ObjectMapper objectMapper;
    private static final Logger LOGGER = Logger.getLogger(LucerneDocumentWriter.class.getName());

    public LucerneDocumentWriter(Path indexPath) throws IOException {
        this.analyzer = new StandardAnalyzer();
        this.directory = FSDirectory.open(indexPath);
        var config = new IndexWriterConfig(analyzer);
        config.setSimilarity(new BM25Similarity());
        this.writer = new IndexWriter(directory, config);
        this.objectMapper = new ObjectMapper();
    }

    public void add(List<Document> documents)  throws IOException {
        for (Document document : documents) {
            var luceneDoc = new org.apache.lucene.document.Document();
            luceneDoc.add(new org.apache.lucene.document.StringField(ID_FIELD, document.getId(), org.apache.lucene.document.Field.Store.YES));
            luceneDoc.add(new org.apache.lucene.document.TextField(CONTENT_FIELD, document.getText(), org.apache.lucene.document.Field.Store.YES));
            String metadataJson = objectMapper.writeValueAsString(document.getMetadata());
            luceneDoc.add(new org.apache.lucene.document.StoredField(METADATA_FIELD, metadataJson));
            writer.addDocument(luceneDoc);
            LOGGER.info("Added document to Lucene index: " + document.getMetadata());
        }
        writer.commit();

    }


    @Override
    public void close() throws Exception {
        writer.close();
        directory.close();
        analyzer.close();
    }
}
