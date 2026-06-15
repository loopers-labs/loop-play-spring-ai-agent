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
 * <h3>왜 RAG가 필요한가</h3>
 * <ul>
 *     <li><b>LLM이 모르는 지식</b>: 배달의 환불/지연 정책은 LLM 학습 데이터에 없다.</li>
 *     <li><b>최신성</b>: 정책은 월 단위로 바뀔 수 있다. 파인튜닝으로 따라갈 수 없다.</li>
 *     <li><b>도메인 특화</b>: "비 오는 날 보상 기준"처럼 배달 고유 규칙이 존재.</li>
 * </ul>
 *
 * <h3>구성 요소</h3>
 * <ul>
 *     <li>{@link VectorStore}: {@code PgVectorStore}가 자동 구성으로 주입된다
 *         ({@code spring-ai-starter-vector-store-pgvector} + {@code application.yml}).</li>
 *     <li>{@link TokenTextSplitter}: 긴 문서를 토큰 단위 청크로 쪼개는 Splitter.
 *         청크 크기와 오버랩은 "검색 정확도 vs 컨텍스트 보존"의 트레이드오프다.</li>
 *     <li>{@link QuestionAnswerAdvisor}: 사용자 질문을 받아 자동으로 VectorStore를 검색하고,
 *         Top-K 결과를 프롬프트에 주입하는 Advisor.</li>
 * </ul>
 *
 * <h3>Advisor 체인 순서</h3>
 * <pre>
 *   MessageChatMemoryAdvisor   (order=10)  — 3주차: 이전 대화 이력 주입
 *   QuestionAnswerAdvisor      (order=20)  — 4주차: RAG 검색 결과 주입
 *   PerformanceLoggingAdvisor  (order=100) — 1주차: 최종 호출 시간 집계
 * </pre>
 * 낮은 order가 먼저 실행된다. Memory가 먼저 대화 맥락을 붙인 뒤 Q&A가 검색하는 순서가
 * 자연스럽다 — "아까 그 주문"의 정책을 묻는 질문을 처리하려면 Memory가 먼저 필요하다.
 */
@Configuration
public class RagConfig {

    /**
     * 검색 시 반환할 Top-K.
     * 너무 크면 프롬프트가 길어져 LLM이 혼동하고, 너무 작으면 관련 정책을 놓친다.
     * 배달 상담처럼 정책 수가 많지 않은 도메인은 4 정도가 적정하다.
     */
    private static final int TOP_K = 4;

    /**
     * 유사도 임계값 (COSINE_SIMILARITY 기준 0.0 ~ 1.0).
     * 이 값 미만의 결과는 "관련 없음"으로 걸러진다.
     * 0.5는 대체로 "주제가 같은 정도"를 의미하며, 도메인/임베딩 모델에 따라 조정 필요.
     */
    private static final double SIMILARITY_THRESHOLD = 0.5;

    /**
     * Spring AI의 기본 {@code TokenTextSplitter} 설정.
     * <p>
     * 배달 정책 문서는 짧고 조항 단위로 끊어져 있어 기본값 (청크 800 토큰 / 오버랩 350)보다
     * 작게 잡아도 된다. 여기서는 기본값으로 두되, 숙제 2단계에서 값을 바꿔보며 관찰한다.
     *
     * <ul>
     *     <li>{@code chunkSize}: 청크 한 개의 목표 토큰 수</li>
     *     <li>{@code minChunkSizeChars}: 이보다 작으면 앞 청크에 병합</li>
     *     <li>{@code minChunkLengthToEmbed}: 이보다 짧으면 임베딩 대상에서 제외</li>
     *     <li>{@code maxNumChunks}: 한 문서에서 생성할 최대 청크 수</li>
     *     <li>{@code keepSeparator}: 문단 구분자를 남길지 여부</li>
     * </ul>
     */
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(
                800,   // chunkSize
                350,   // minChunkSizeChars
                5,     // minChunkLengthToEmbed
                10_000,// maxNumChunks
                true   // keepSeparator
        );
    }

    /**
     * 검색 + 프롬프트 주입을 자동화하는 Advisor.
     * <p>
     * 동작:
     * <ol>
     *     <li>사용자 질문을 임베딩으로 변환</li>
     *     <li>VectorStore에서 Top-K 유사 문서 조회 (임계값 이하는 제외)</li>
     *     <li>검색 결과를 시스템 프롬프트 뒤에 "Context:" 블록으로 추가</li>
     *     <li>LLM이 해당 Context를 근거로 답을 생성</li>
     * </ol>
     */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
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
