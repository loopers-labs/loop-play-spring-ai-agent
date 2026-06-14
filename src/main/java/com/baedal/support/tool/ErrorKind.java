package com.baedal.support.tool;

/**
 * Tool 조회/처리 중 발생한 <b>시스템 오류</b>의 성격.
 * <p>
 * LLM이 고객에게 어떤 복구 안내를 할지 결정하는 신호다. 판별 기준은 "같은 요청을 잠시 후 다시 하면 성공할 수 있나?"
 * <ul>
 *     <li>{@link #TRANSIENT} — 일시적 인프라 오류(DB 타임아웃·연결·락 등). 잠시 후 재시도하면 성공할 수 있다.</li>
 *     <li>{@link #PERMANENT} — 같은 요청을 다시 해도 실패하는 결함(코드 버그·제약 위반 등). 상담사 연결이 필요하다.</li>
 * </ul>
 */
public enum ErrorKind {
    TRANSIENT,
    PERMANENT
}
