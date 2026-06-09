package com.baedal.support.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.prompt.PromptTemplate;
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
 *     <li>{@link VectorStore}: {@code PgVectorStore} 가 자동 구성으로 주입된다
 *         ({@code spring-ai-starter-vector-store-pgvector} + {@code application.yml}).</li>
 *     <li>{@link TokenTextSplitter}: 긴 문서를 토큰 단위 청크로 쪼개는 Splitter.
 *         청크 크기는 "검색 정확도 vs 컨텍스트 보존"의 트레이드오프 (2단계 실험: 100 / 800 / 2000).</li>
 *     <li>{@link QuestionAnswerAdvisor}: 사용자 질문을 자동으로 임베딩→검색하고
 *         Top-K 결과를 프롬프트에 주입하는 Advisor.</li>
 * </ul>
 *
 * <h3>Advisor 체인 순서</h3>
 * <pre>
 *   MessageChatMemoryAdvisor   (order=10)   — 3주차: 이전 대화 이력 주입
 *   QuestionAnswerAdvisor      (order=20)   — 4주차: RAG 검색 결과 주입
 *   PerformanceLoggingAdvisor  (order=100)  — 1주차: 최종 호출 토큰·시간 집계
 * </pre>
 * 관례상 Memory(10) 뒤에 RAG(20)를 두지만, 기본 QA Advisor 는 현재 user query 텍스트를 그대로 임베딩하므로
 * (Memory 가 쿼리를 재작성하지 않음) 순서를 20→5 로 바꿔도 검색 결과·메시지 구조가 동일했다 — 3단계 관찰(reports/week4/stage3).
 *
 * @see KnowledgeLoader FAQ/정책 문서를 VectorStore 에 적재하는 ApplicationRunner
 */
@Configuration
public class RagConfig {

    // similaritySearch 가 반환할 상위 N건. 정책 7건 기준 1은 누락 위험·10은 토큰 폭증 → 4.
    // K sweep(threshold off, 1/4/7/10): 입력토큰 4481→5782→6979, K=7=K=10(문서 7건뿐이라 상한).
    // K>7 은 무의미, K=4 는 토큰 ~83% 로 상위 4건 확보. (reports/week4/stage1)
    private static final int TOP_K = 4;

    // COSINE_SIMILARITY 기준 0.0~1.0. 이 미만은 "관련 없음"으로 버린다.
    // 너무 낮으면 도메인 밖 질문에도 무관 정책이 끼어 환각, 너무 높으면 정답 문서도 탈락 → Fallback.
    // qwen3-embedding 기준 출발점 0.5. (0.3 / 0.5 / 0.7 비교는 3단계에서 실험)
    private static final double SIMILARITY_THRESHOLD = 0.5;

    // 배달 정책 문서는 조항 단위로 이미 쪼개져 있어 800/350 기본값으로 대부분 한 청크에 담긴다.
    //   800    chunkSize           : 청크 한 개 목표 토큰 수
    //   350    minChunkSizeChars   : 이보다 작으면 앞 청크에 병합
    //   5      minChunkLengthToEmbed: 이보다 짧으면 임베딩 제외
    //   10_000 maxNumChunks
    //   true   keepSeparator       : 문단 구분자 유지
    @Bean
    public TokenTextSplitter tokenTextSplitter() {
        return new TokenTextSplitter(800, 350, 5, 10_000, true);
    }

    // QA 기본 템플릿의 독소 두 줄("not prior knowledge" + "답이 context 에 없으면 못 답한다고 하라")을 교체.
    // 기본 템플릿은 모든 질문에 적용돼, 정책과 무관한 질문(주문 위치·"그거" 등)이 빈 Context 에 걸려
    // Memory/Tool 을 못 쓰고 무조건 되묻는다(0/5 관찰). 아래 템플릿은 "정책이면 Context, 무관하면 평소대로"로 푼다.
    // {query}=사용자 질문, {question_answer_context}=검색된 정책 청크. (QuestionAnswerAdvisor 의 필수 placeholder)
    private static final PromptTemplate QA_PROMPT_TEMPLATE = new PromptTemplate("""
            {query}

            아래는 참고용 배달 정책 문서입니다. (질문과 무관하면 무시하세요)
            ---------------------
            {question_answer_context}
            ---------------------

            - 환불·취소·배달 지연 보상·쿠폰 등 정책 질문이면 위 문서를 근거로 답하세요.
            - 위 문서와 무관한 질문(주문 위치·상태 조회, 이전 대화의 "그거"/"아까 그 주문" 등)이면,
              위 문서를 무시하고 대화 이력과 Tool 을 평소대로 활용해 답하세요.
            - "정책 문서에 없으니 답할 수 없다"고 임의로 거절하지 마세요.
              (정책 질문인데 문서에서 못 찾을 때만 "확인이 필요합니다, 상담원 연결로 도와드리겠습니다".)
            """);

    // order(20): Memory(10) 뒤, Performance(100) 앞 (관례). 단 QA Advisor 는 현재 user query 를 임베딩하므로
    // Memory 순서가 검색 쿼리를 바꾸지는 않는다(3단계 실험: 20↔5 차이 없음). 쿼리 재작성은 RetrievalAugmentationAdvisor 영역.
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();

        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .promptTemplate(QA_PROMPT_TEMPLATE)
                .order(20)
                .build();
    }
}
