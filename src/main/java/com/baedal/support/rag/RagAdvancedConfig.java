package com.baedal.support.rag;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * [선택 과제 4.4] RetrievalAugmentationAdvisor(RAA) 심화 — `@Profile("raa")` 에서만 활성.
 *
 * <p>stock {@code QuestionAnswerAdvisor}는 '현재 턴 UserMessage 텍스트'만 임베딩해 advisor 순서가
 * 검색에 무관했다(PART M). RAA는 검색 파이프라인을 부품으로 분해해
 * <b>QueryTransformer로 대화 이력을 질의에 반영(복원)</b>한 뒤 검색할 수 있다.
 *
 * <ul>
 *   <li>{@link CompressionQueryTransformer}: 채팅 모델로 '대화 이력 + 후속 질문'을 standalone 질의로 압축
 *       → "아까 그 주문 환불 돼요?" 를 이력 속 2024-1234 로 복원 시도.</li>
 *   <li>{@link VectorStoreDocumentRetriever}: topK/threshold/filter 검색.</li>
 *   <li>{@link ContextualQueryAugmenter}: 컨텍스트 주입 + 빈 컨텍스트 처리(allowEmptyContext).</li>
 * </ul>
 */
@Configuration
@Profile("raa")
public class RagAdvancedConfig {

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(VectorStore vectorStore,
                                                                     ChatClient.Builder chatClientBuilder) {
        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(
                        CompressionQueryTransformer.builder()
                                .chatClientBuilder(chatClientBuilder.build().mutate())
                                .build())
                .documentRetriever(
                        VectorStoreDocumentRetriever.builder()
                                .vectorStore(vectorStore)
                                .similarityThreshold(0.45)
                                .topK(4)
                                .build())
                .queryAugmenter(
                        ContextualQueryAugmenter.builder()
                                .allowEmptyContext(true)   // 빈 컨텍스트여도 강제 거절 X (tool 경로·일반 응답 보존)
                                .build())
                .order(20)
                .build();
    }
}
