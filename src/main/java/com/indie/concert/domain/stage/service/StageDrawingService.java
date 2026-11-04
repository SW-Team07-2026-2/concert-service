package com.indie.concert.domain.stage.service;

import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.stage.entity.StageDrawingEntity;
import com.indie.concert.domain.stage.repository.StageDrawingRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/** 무대 그리기 저장 (가안) — 형식이 없어서 해석하지 않고 원본 그대로 둡니다 */
@Service
public class StageDrawingService {

    private final StageDrawingRepository stageDrawings;
    private final VenueRepository venueRepository;
    private final Clock clock;

    public StageDrawingService(StageDrawingRepository stageDrawings, VenueRepository venueRepository, Clock clock) {
        this.stageDrawings = stageDrawings;
        this.venueRepository = venueRepository;
        this.clock = clock;
    }

    /** 없는 공연장이면 404 · 이미 있으면 덮어씀. 받은 시각을 돌려줍니다 */
    @Transactional
    public OffsetDateTime save(long venueId, String payload) {
        if (venueRepository.findById(venueId).isEmpty()) throw new GeneralException(CommonErrorCode.NOT_FOUND);
        OffsetDateTime now = OffsetDateTime.now(clock);
        StageDrawingEntity drawing = stageDrawings.findById(venueId).orElseGet(() -> new StageDrawingEntity(venueId));
        drawing.replace(payload, now);
        stageDrawings.save(drawing);
        return now;
    }
}
