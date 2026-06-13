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

            Document doc = new Document(
                    faq.id(),
                    faq.content(),
                    Map.of(
                            "faqId", faq.id(),
                            "title", faq.title(),
                            "category", faq.category()
                    ));

            // 긴 문서는 TokenTextSplitter로 청크로 쪼갠다.
            // 쪼개진 청크들은 원본 metadata를 상속한다.
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
     * 파일명 컨벤션에서 id/category를 추출한다.
     * <p>
     * 컨벤션: {@code {category}__{id}.md}
     * <p>
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
     * 전략: "매우 일반적인 쿼리"로 similaritySearch를 돌리며 filter로 faqId를 걸어
     * hit가 1건이라도 나오면 적재된 것으로 간주한다.
     * <p>
     * 프로덕션에서는 별도의 audit 테이블을 두거나, VectorStore의 {@code delete} API로
     * id를 직접 조회하는 편이 낫다 — 여기서는 교육용이므로 단순 접근을 선택했다.
     */
    private boolean alreadyLoaded(String faqId) {
        SearchRequest req = SearchRequest.builder()
                .query("정책")   // 아무 쿼리나 OK — filter로만 걸러짐
                .topK(1)
                .similarityThresholdAll()
                .filterExpression("faqId == '" + faqId + "'")
                .build();
        return !vectorStore.similaritySearch(req).isEmpty();
    }
}
