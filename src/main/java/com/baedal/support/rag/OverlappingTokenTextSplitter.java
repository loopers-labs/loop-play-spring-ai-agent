package com.baedal.support.rag;

import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.ArrayList;
import java.util.List;

/**
 * 4주차 — 청크 오버랩 실험용 커스텀 Splitter.
 *
 * <p>Spring AI의 {@link TokenTextSplitter}는 오버랩 파라미터가 없어 항상 0(순차 분할, 겹침 없음)이다.
 * 이 클래스는 그 위에 <b>문자 단위 오버랩</b>을 얹어, 설계 결정 2(오버랩)를 정량 비교할 수 있게 한다.
 *
 * <h3>동작</h3>
 * <ol>
 *   <li>{@code super.splitText()}로 기본(비오버랩) 청크 리스트를 만든다.</li>
 *   <li>{@code overlapChars > 0}이면, 2번째 청크부터 <b>앞 청크의 마지막 {@code overlapChars}자</b>를
 *       앞에 덧붙인다 → 경계에서 잘린 문장/조항이 다음 청크에도 함께 들어가 "조각남"을 완화한다.</li>
 *   <li>{@code overlapChars == 0}이면 기본 동작과 100% 동일(투명).</li>
 * </ol>
 *
 * <p>오버랩은 {@code splitText(String)} 단위(=문서 1건의 본문) 안에서만 적용되므로 문서 경계를 넘지 않는다.
 */
public class OverlappingTokenTextSplitter extends TokenTextSplitter {

    private final int overlapChars;

    public OverlappingTokenTextSplitter(int chunkSize, int minChunkSizeChars, int minChunkLengthToEmbed,
                                        int maxNumChunks, boolean keepSeparator, int overlapChars) {
        super(chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator);
        this.overlapChars = overlapChars;
    }

    @Override
    protected List<String> splitText(String text) {
        List<String> base = super.splitText(text);
        if (overlapChars <= 0 || base.size() <= 1) {
            return base;
        }
        List<String> out = new ArrayList<>(base.size());
        out.add(base.get(0));
        for (int i = 1; i < base.size(); i++) {
            String prev = base.get(i - 1);
            String tail = prev.length() <= overlapChars
                    ? prev
                    : prev.substring(prev.length() - overlapChars);
            out.add(tail + "\n" + base.get(i));   // 앞 청크 꼬리 + 현재 청크
        }
        return out;
    }
}
