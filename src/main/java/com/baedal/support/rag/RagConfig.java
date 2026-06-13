package com.baedal.support.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 설정 — Vector Store + 청킹 + QuestionAnswerAdvisor.
 *
 * <p>Advisor 체인 순서 (order 기준):
 * <pre>
 *   MessageChatMemoryAdvisor   order=10   이전 대화 이력 주입
 *   QuestionAnswerAdvisor      order=20   RAG 검색 결과 주입
 *   PerformanceLoggingAdvisor  order=100  호출 시간 로깅
 * </pre>
 * Memory가 먼저 대화 맥락을 복원해야 RAG가 정확한 쿼리로 검색할 수 있다.
 *
 * @see KnowledgeLoader 정책 문서를 VectorStore에 적재하는 ApplicationRunner
 */
@Configuration
public class RagConfig {

    private static final int TOP_K = 4;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(800, 350, 5, 10_000, true);
    }

    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .order(20)
                .build();
    }
}
