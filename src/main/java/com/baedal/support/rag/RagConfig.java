package com.baedal.support.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Round 4 — RAG 설정 (Vector Store + 청킹 + QuestionAnswerAdvisor).
 *
 * <h3>Advisor 체인 순서</h3>
 * <pre>
 *   MessageChatMemoryAdvisor   (order=10)   — Round 3: 이전 대화 이력 주입
 *   QuestionAnswerAdvisor      (order=20)   — Round 4: RAG 검색 결과 주입
 *   PerformanceLoggingAdvisor  (order=100)  — Round 1: 최종 호출 시간 집계
 * </pre>
 * Memory가 먼저 "아까 그 주문"의 orderId를 복원해야 RAG가 "그 주문의 환불 정책"을
 * 검색할 수 있다. 순서를 바꾸면 어떤 품질 저하가 생기는지는 숙제 3단계에서 관찰한다.
 *
 * @see KnowledgeLoader FAQ/정책 문서를 VectorStore에 적재하는 ApplicationRunner
 */
@Configuration
public class RagConfig {

    /**
     * similaritySearch가 돌려줄 상위 N건.
     * 정책 문서가 7건 내외라 4면 "환불 + 지연" 같은 복합 질문도 관련 조항을 덮으면서
     * 프롬프트 토큰 폭증을 피한다. (1=관련 정책 놓침, 10=원문 10개로 입력 토큰 폭증)
     */
    private static final int TOP_K = 4;

    /**
     * COSINE_SIMILARITY 기준. 이 값 미만은 "관련 없음"으로 버린다.
     * 0.5는 대략 "주제가 같은 정도"이며 qwen3-embedding:0.6b 기준 출발점이다.
     * 너무 낮으면 도메인 밖 질문에도 무관 정책이 Top-K에 끼고, 너무 높으면 정답 문서도 탈락한다.
     * 경계 탐색은 숙제 3단계 실험으로 남긴다.
     */
    private static final double SIMILARITY_THRESHOLD = 0.5;

    /**
     * 문서를 토큰 단위 청크로 쪼개는 Splitter.
     * 배달 정책 문서는 조항 단위로 이미 끊겨 있어 800/350 기본값으로 대체로 잘 동작한다.
     */
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(
                800,    // chunkSize: 청크 한 개의 목표 토큰 수
                350,    // minChunkSizeChars: 이보다 작으면 앞 청크에 병합
                5,      // minChunkLengthToEmbed: 이보다 짧으면 임베딩 제외
                10_000, // maxNumChunks
                true    // keepSeparator (문단 구분자 유지)
        );
    }

    /**
     * 사용자 질문을 자동으로 벡터화 → VectorStore 검색 → Top-K 결과를 프롬프트에 주입하는 Advisor.
     * order(20)으로 Memory(10) 뒤, Performance(100) 앞에 놓는다.
     */
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
