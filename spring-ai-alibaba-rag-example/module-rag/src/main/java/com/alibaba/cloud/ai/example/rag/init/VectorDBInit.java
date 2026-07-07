/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.cloud.ai.example.rag.init;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.KeywordProperty;
import co.elastic.clients.elasticsearch._types.mapping.ObjectProperty;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TextProperty;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.CreateIndexResponse;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.elasticsearch.autoconfigure.ElasticsearchVectorStoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * @author yuluo
 * @author <a href="mailto:yuluo08290126@gmail.com">yuluo</a>
 */

@Component
public class VectorDBInit {

	private static final Logger logger = LoggerFactory.getLogger(VectorDBInit.class);

	private final VectorStore vectorStore;

	@Value("classpath:documents/story-1.md")
	Resource file1;

	@Value("classpath:documents/story-2.md")
	Resource file2;

	private final ElasticsearchClient elasticsearchClient;

	private final ElasticsearchVectorStoreProperties options;

	private static final String textField = "content";

	private static final String vectorField = "embedding";

	public VectorDBInit(
			VectorStore vectorStore,
			ElasticsearchClient elasticsearchClient,
			ElasticsearchVectorStoreProperties options) {

		this.vectorStore = vectorStore;
		this.elasticsearchClient = elasticsearchClient;
		this.options = options;
	}

	@PostConstruct
	void run() {

		logger.info("Loading *.md files as Documents");

		// MarkdownDocumentReader 会把 Markdown 文件读成 Spring AI 的 Document。
		// metadata 用来保存文档来源、地点、业务标签等额外信息，后续检索结果可以携带这些上下文。
		var markdownReader1 = new MarkdownDocumentReader(file1, MarkdownDocumentReaderConfig.builder()
				.withAdditionalMetadata("location", "North Pole")
				.build());
		List<Document> documents = new ArrayList<>(markdownReader1.get());

		// 第二份文档使用不同的 metadata，方便观察多文档进入同一个向量库后的召回效果。
		var markdownReader2 = new MarkdownDocumentReader(file2, MarkdownDocumentReaderConfig.builder()
				.withAdditionalMetadata("location", "Italy")
				.build());
		List<Document> secondDocuments = markdownReader2.get();
		documents.addAll(secondDocuments);

		logger.info("Creating and storing Embeddings from Documents");

		createIndexIfNotExists();
		// 原始文档通常太长，不能整篇直接放进 Prompt。
		// TokenTextSplitter 会按 token 粒度切成更小片段，每个片段都会生成 embedding 并写入 VectorStore。
		List<Document> splitDocuments = new TokenTextSplitter().split(documents);
		vectorStore.add(splitDocuments);
	}

	private void createIndexIfNotExists() {
		try {
			String indexName = options.getIndexName();
			Integer dimsLength = options.getDimensions();

			if (!StringUtils.hasLength(indexName)) {
				throw new IllegalArgumentException("Elastic search index name must be provided");
			}

			boolean exists = elasticsearchClient.indices().exists(idx -> idx.index(indexName)).value();
			if (exists) {
				logger.debug("Index {} already exists. Skipping creation.", indexName);
				return;
			}

			String similarityAlgo = options.getSimilarity().name();
			IndexSettings indexSettings = IndexSettings
					.of(settings -> settings.numberOfShards(String.valueOf(1)).numberOfReplicas(String.valueOf(1)));

			// Elasticsearch 向量索引至少需要两类字段：
			// 1. content：原始文本片段，用于最终注入 Prompt；
			// 2. embedding：文本片段对应的向量，用于相似度检索。
			Map<String, Property> properties = new HashMap<>();
			properties.put(vectorField, Property.of(property -> property.denseVector(
					DenseVectorProperty.of(dense -> dense.index(true).dims(dimsLength).similarity(DenseVectorSimilarity.valueOf(similarityAlgo))))));
			properties.put(textField, Property.of(property -> property.text(TextProperty.of(t -> t))));

			// metadata 不参与向量相似度计算，但可以保存来源信息，方便后续做过滤、溯源或展示引用。
			Map<String, Property> metadata = new HashMap<>();
			metadata.put("ref_doc_id", Property.of(property -> property.keyword(KeywordProperty.of(k -> k))));

			properties.put("metadata",
					Property.of(property -> property.object(ObjectProperty.of(op -> op.properties(metadata)))));

			CreateIndexResponse indexResponse = elasticsearchClient.indices()
					.create(createIndexBuilder -> createIndexBuilder.index(indexName)
							.settings(indexSettings)
							.mappings(TypeMapping.of(mappings -> mappings.properties(properties))));

			if (!indexResponse.acknowledged()) {
				throw new RuntimeException("failed to create index");
			}

			logger.info("create elasticsearch index {} successfully", indexName);
		}
		catch (IOException e) {
			logger.error("failed to create index", e);
			throw new RuntimeException(e);
		}
	}

}
