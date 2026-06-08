package com.baedal.support.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Round 4 — 정책/FAQ 문서를 VectorStore에 적재하는 ApplicationRunner.
 *
 * <h3>동작 순서</h3>
 * <ol>
 *     <li>{@code classpath:knowledge/*.md} 파일을 모두 읽는다.</li>
 *     <li>파일명에서 id/category를 뽑아 {@link FaqDocument}로 변환한다.</li>
 *     <li>각 문서를 Spring AI {@link Document}로 변환하며 metadata를 심는다.</li>
 *     <li>{@link TokenTextSplitter}로 긴 문서를 청크로 쪼갠다.</li>
 *     <li>이미 적재된 id가 있으면 스킵, 없으면 VectorStore에 저장한다.</li>
 * </ol>
 *
 * <h3>왜 ApplicationRunner인가</h3>
 * {@code @PostConstruct}는 Bean 초기화 단계라 VectorStore의 DataSource/schema가
 * 아직 준비 안 됐을 수 있다. ApplicationRunner는 ApplicationContext 기동이
 * 완료된 후 실행되므로 {@code initialize-schema} 이후 안전하게 동작한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeLoader implements ApplicationRunner {

    private final VectorStore vectorStore;
    private final TokenTextSplitter tokenTextSplitter;

    private static final String KNOWLEDGE_LOCATION = "classpath:/knowledge/*.md";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources(KNOWLEDGE_LOCATION);

        if (resources.length == 0) {
            log.warn("[KnowledgeLoader] knowledge 리소스가 없습니다 — RAG 시드 스킵");
            return;
        }

        int loaded = 0;
        int skipped = 0;

        for (Resource resource : resources) {
            FaqDocument faq = parse(resource);

            if (alreadyLoaded(faq.id())) {
                skipped++;
                log.debug("[KnowledgeLoader] 이미 적재됨 — id={} ({})", faq.id(), faq.title());
                continue;
            }

            // FaqDocument → Spring AI Document 변환 (metadata로 faqId/title/category 저장)
            //   - faqId: alreadyLoaded()가 filterExpression으로 중복을 검사하는 키
            //   - title: 검색 후 "어떤 정책을 인용했는지" 출처 로깅용
            //   - category: 필요 시 카테고리 한정 검색(filterExpression) 용도
            Document doc = new Document(
                    faq.id(),
                    faq.content(),
                    Map.of(
                            "faqId", faq.id(),
                            "title", faq.title(),
                            "category", faq.category()
                    ));

            // 청킹 후 add() — add() 내부에서 EmbeddingModel이 각 청크를 벡터로 변환해 저장한다.
            List<Document> chunks = tokenTextSplitter.apply(List.of(doc));
            vectorStore.add(chunks);
            loaded++;

            log.info("[KnowledgeLoader] 적재 완료 — id={} / 청크={}개 / 카테고리={}",
                    faq.id(), chunks.size(), faq.category());
        }

        log.info("[KnowledgeLoader] RAG 시드 완료 — 신규 {}건 / 스킵 {}건 / 총 {}건",
                loaded, skipped, resources.length);
    }

    /**
     * 파일명 컨벤션 {@code {category}__{id}.md}에서 id/category를 추출한다.
     * 예: {@code refund__refund-basic.md} → category=refund, id=refund-basic
     */
    private FaqDocument parse(Resource resource) throws Exception {
        String filename = resource.getFilename();  // refund__refund-basic.md
        if (filename == null) {
            throw new IllegalStateException("리소스 파일명을 읽을 수 없습니다: " + resource);
        }
        String base = filename.replaceFirst("\\.md$", "");

        String category;
        String id;
        int sep = base.indexOf("__");
        if (sep > 0) {
            category = base.substring(0, sep);
            id = base.substring(sep + 2);
        } else {
            category = "general";
            id = base;
        }

        String body;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        }

        // 첫 번째 '# ' 라인을 제목으로 사용
        String title = body.lines()
                .filter(l -> l.startsWith("# "))
                .findFirst()
                .map(l -> l.substring(2).trim())
                .orElse(id);

        return new FaqDocument(id, title, category, body);
    }

    /**
     * 같은 faqId로 이미 VectorStore에 저장된 문서가 있는지 확인한다.
     * <p>
     * VectorStore 인터페이스에는 "id로 한 건 조회"가 없어, 유사도는 무시(threshold all)하고
     * metadata filter로만 걸러 한 건이라도 있으면 true를 돌려준다.
     * <p>
     * 한계: 문서 "내용이 바뀐 경우"는 감지하지 못한다. 프로덕션은 해시(SHA-256) 기반 재적재가 낫다.
     */
    private boolean alreadyLoaded(String faqId) {
        SearchRequest req = SearchRequest.builder()
                .query("정책")                       // 아무 쿼리나 OK — filter로만 걸러짐
                .topK(1)
                .similarityThresholdAll()            // 유사도 임계값 없음
                .filterExpression("faqId == '" + faqId + "'")
                .build();
        return !vectorStore.similaritySearch(req).isEmpty();
    }
}
