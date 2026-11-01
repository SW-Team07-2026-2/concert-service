package com.indie.concert.global.common.util;

import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.config.ConcertProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** size 파라미터 — 기본 20 · 최대 50 (08 §0.6) */
@Component
public class PageSize {

    private final ConcertProperties.Page props;

    public PageSize(ConcertProperties props) {
        this.props = props.page();
    }

    public int resolve(Integer requested) {
        if (requested == null || requested <= 0) return props.size();
        return Math.min(requested, props.maxSize());
    }

    /** 목록 파라미터 상한 (캘린더 artistIds) — 한 화면 분량 */
    public void checkIds(List<Long> ids) {
        if (ids.size() > props.maxSize()) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "한 번에 최대 " + props.maxSize() + "개까지 조회할 수 있습니다");
        }
    }
}
