package com.baedal.support.rag;

/**
 * 4주차 — FAQ 시드 문서 도메인.
 * <p>
 * VectorStore 에 적재되기 전의 "원본 지식 조각"을 표현한다.
 * Spring AI 의 {@code org.springframework.ai.document.Document} 로 변환하기 전 단계다.
 *
 * <ul>
 *     <li>{@code id}       : 시드 파일 고유 식별자 (예: {@code refund-basic}). 중복 적재 방지 키.</li>
 *     <li>{@code title}    : 원문 섹션 제목. 검색 결과를 사람이 확인할 때 유용.</li>
 *     <li>{@code category} : refund / delivery-delay / coupon / cancel / account 등. 필터 검색에 활용.</li>
 *     <li>{@code content}  : 실제 임베딩 대상 본문. 길면 {@code TokenTextSplitter} 가 청크로 쪼갠다.</li>
 * </ul>
 */
public record FaqDocument(
        String id,
        String title,
        String category,
        String content
) {}
