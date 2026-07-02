package com.finbank.backend.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 페이징 응답 공통 래퍼. Spring Data의 Page를 클라이언트 친화적 형태로 변환한다.
 * 거래 내역 등 목록 조회에서 재사용한다.
 *
 * @param <T> 페이지에 담기는 요소 타입
 */
public class PageResponse<T> {

    /** 현재 페이지의 데이터 목록 */
    private List<T> content;
    /** 현재 페이지 번호 (0부터 시작) */
    private int page;
    /** 페이지 크기 */
    private int size;
    /** 전체 요소 수 */
    private long totalElements;
    /** 전체 페이지 수 */
    private int totalPages;
    /** 첫 페이지 여부 */
    private boolean first;
    /** 마지막 페이지 여부 */
    private boolean last;

    public PageResponse(Page<T> pageData) {
        this.content = pageData.getContent();
        this.page = pageData.getNumber();
        this.size = pageData.getSize();
        this.totalElements = pageData.getTotalElements();
        this.totalPages = pageData.getTotalPages();
        this.first = pageData.isFirst();
        this.last = pageData.isLast();
    }

    public List<T> getContent() {
        return content;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }

    public long getTotalElements() {
        return totalElements;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public boolean isFirst() {
        return first;
    }

    public boolean isLast() {
        return last;
    }
}