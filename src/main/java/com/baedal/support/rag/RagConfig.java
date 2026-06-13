package com.baedal.support.rag;

import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
        SearchRequest searchRequest = SearchRequest.builder()
                .topK(TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD)
                .build();

        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(searchRequest)
                .order(5)  // Memory(10) 뒤, Performance(100) 앞
                .build();
    }
}
