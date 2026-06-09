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
 * 4주차 — 정책/FAQ 문서를 VectorStore 에 적재하는 ApplicationRunner.
 *
 * <h3>동작 순서</h3>
 * <ol>
 *     <li>{@code classpath:/knowledge/*.md} 파일을 모두 읽는다.</li>
 *     <li>파일명({@code {category}__{id}.md})에서 id/category 를 뽑아 {@link FaqDocument} 로 변환한다.</li>
 *     <li>각 문서를 Spring AI {@link Document} 로 변환하며 metadata(faqId/title/category)를 심는다.</li>
 *     <li>{@link TokenTextSplitter} 로 청크를 쪼갠다.</li>
 *     <li>이미 적재된 id 면 스킵, 아니면 VectorStore 에 저장한다(임베딩은 add() 내부에서 호출).</li>
 * </ol>
 *
 * <h3>왜 ApplicationRunner 인가</h3>
 * {@code @PostConstruct} 는 Bean 초기화 단계라 VectorStore 의 DataSource/schema 가 아직
 * 준비 안 됐을 수 있다. ApplicationRunner 는 ApplicationContext 기동 완료 후 실행되므로
 * {@code initialize-schema} 로 만들어진 vector_store 테이블 위에서 안전하게 add() 한다.
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

            // metadata 의 3개 키 의도:
            //   faqId    → 아래 alreadyLoaded() 의 filterExpression 중복 방지 키
            //   title    → 검색 후 "어떤 정책 문서를 인용했는지" 출처 로깅
            //   category → 필요시 "환불 카테고리 안에서만 검색" 같은 필터
            Document doc = new Document(
                    faq.id(),
                    faq.content(),
                    Map.of(
                            "faqId", faq.id(),
                            "title", faq.title(),
                            "category", faq.category()
                    ));

            // 청크는 원본 metadata 를 상속한다 — 한 문서가 N청크로 쪼개져도 모두 faqId 가 붙는다.
            // add() 내부에서 EmbeddingModel(qwen3-embedding:0.6b) 호출 → 벡터화 후 vector_store INSERT.
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
     * 파일명 컨벤션 {@code {category}__{id}.md} 에서 id/category 를 추출하고 본문/제목을 읽는다.
     * (교육 범위 밖이라 완성 상태로 제공)
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

        String title = body.lines()
                .filter(l -> l.startsWith("# "))
                .findFirst()
                .map(l -> l.substring(2).trim())
                .orElse(id);

        return new FaqDocument(id, title, category, body);
    }

    /**
     * 같은 faqId 로 이미 저장된 문서가 있는지 확인한다.
     * <p>
     * VectorStore 인터페이스에는 "id 로 한 건 조회"가 없어, 필요한 yes/no 만 얻기 위해
     * similaritySearch + metadata filter 를 쓴다. query 는 형식상 필요할 뿐 filter 로만 걸러진다.
     * <p>
     * 한계: 문서 "내용이 바뀐 경우"는 감지 못 한다(같은 id 면 무조건 스킵).
     * 프로덕션은 내용 해시(SHA-256)를 metadata 에 넣어 "바뀌면 재적재"하는 편이 낫다.
     */
    private boolean alreadyLoaded(String faqId) {
        SearchRequest req = SearchRequest.builder()
                .query("정책")
                .topK(1)
                .similarityThresholdAll()
                .filterExpression("faqId == '" + faqId + "'")
                .build();
        return !vectorStore.similaritySearch(req).isEmpty();
    }
}
