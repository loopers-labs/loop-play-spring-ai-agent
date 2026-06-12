package com.baedal.support.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 4주차 — RAG 설정 (Vector Store + 청킹 + QuestionAnswerAdvisor).
 *
 * <h3>구성 요소</h3>
 * <ul>
 *     <li>{@link VectorStore}: {@code PgVectorStore}가 자동 구성으로 주입된다
 *         ({@code spring-ai-starter-vector-store-pgvector} + {@code application.yml}).</li>
 *     <li>{@link TokenTextSplitter}: 긴 문서를 토큰 단위 청크로 쪼개는 Splitter.
 *         청크 크기와 오버랩은 "검색 정확도 vs 컨텍스트 보존"의 트레이드오프.</li>
 *     <li>{@link QuestionAnswerAdvisor}: 사용자 질문을 받아 자동으로 VectorStore를 검색하고,
 *         Top-K 결과를 프롬프트에 주입하는 Advisor.</li>
 * </ul>
 *
 * <h3>Advisor 체인 순서</h3>
 * <pre>
 *   MessageChatMemoryAdvisor   (order=10)   — 3주차: 이전 대화 이력 주입
 *   QuestionAnswerAdvisor      (order=20)   — 4주차: RAG 검색 결과 주입
 *   PerformanceLoggingAdvisor  (order=100)  — 1주차: 최종 호출 시간 집계
 * </pre>
 * Memory가 먼저 "아까 그 주문"의 orderId를 복원해야 RAG가 "그 주문의 환불 정책"을
 * 검색할 수 있다. 순서를 바꾸면 어떤 품질 저하가 생기는지 숙제 3단계에서 관찰한다.
 *
 * @see KnowledgeLoader FAQ/정책 문서를 VectorStore에 적재하는 ApplicationRunner
 */
@Configuration
public class RagConfig {

    private static final int TOP_K = 3;
    private static final double SIMILARITY_THRESHOLD = 0.5;

    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        // TODO: TokenTextSplitter 인스턴스 반환
        return new TokenTextSplitter(
                800,    // chunkSize: 목표 청크 크기(토큰) 800
                350,    // minChunkSizeChars: 이보다 작으면 앞 청크에 병합 350
                5,      // minChunkLengthToEmbed: 이보다 짧으면 임베딩 제외
                10_000, // maxNumChunks
                true    // keepSeparator (문단 구분자 유지)
        );
    }

    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        // TODO: SearchRequest + QuestionAnswerAdvisor 빌드해 반환 (order=20)
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();

        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .order(20)  // Memory(10) 뒤, Performance(100) 앞
                .build();
    }
}
