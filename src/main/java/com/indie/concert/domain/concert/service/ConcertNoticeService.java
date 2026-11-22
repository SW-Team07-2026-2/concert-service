package com.indie.concert.domain.concert.service;

import com.indie.concert.domain.booking.repository.BookingRepository;
import com.indie.concert.domain.concert.dto.event.ConcertEvents;
import com.indie.concert.domain.concert.dto.request.NoticeCreateRequest;
import com.indie.concert.domain.concert.dto.request.NoticeUpdateRequest;
import com.indie.concert.domain.concert.dto.response.ArtistRef;
import com.indie.concert.domain.concert.dto.response.NoticeView;
import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.ConcertNoticeEntity;
import com.indie.concert.domain.concert.repository.ConcertNoticeRepository;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 공연 변경 공지 (11/18) — 관리자(Studio)가 쓰고 · 고치고 · 지웁니다. 공개 공연 상세가 최신순으로 보여 줍니다.
 * 새 공지만 알림을 보냅니다 (concert.notice_posted — 라인업 팔로워 + 확정 예매자). 고치기 · 지우기는 알림이 없습니다.
 * 공연 값(일정 · 공연장 · 예매 오픈 등)은 공지와 따로 공연 수정(PATCH)으로 바꿉니다.
 */
@Service
public class ConcertNoticeService {

    private static final Logger log = LoggerFactory.getLogger(ConcertNoticeService.class);

    private final ConcertNoticeRepository notices;
    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final BookingRepository bookingRepository;
    private final LineupReader lineupReader;
    private final ConcertEvents events;
    private final Clock clock;

    public ConcertNoticeService(ConcertNoticeRepository notices, ConcertRepository concertRepository, VenueRepository venueRepository,
                                BookingRepository bookingRepository, LineupReader lineupReader, ConcertEvents events, Clock clock) {
        this.notices = notices;
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.bookingRepository = bookingRepository;
        this.lineupReader = lineupReader;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<NoticeView> list(long concertId) {
        concert(concertId);
        return notices.findByConcertIdOrderByIdDesc(concertId).stream().map(NoticeView::of).toList();
    }

    @Transactional
    public NoticeView create(long concertId, NoticeCreateRequest r) {
        ConcertEntity c = concert(concertId);
        ConcertNoticeEntity notice = notices.save(new ConcertNoticeEntity(concertId, r.kind(), r.title().strip(), r.body().strip(),
                OffsetDateTime.now(clock)));
        List<Long> artistIds = lineupReader.of(concertId).stream().map(ArtistRef::id).toList();
        List<Long> bookers = bookingRepository.findConfirmedUserIds(concertId);
        events.noticePosted(c, venueRepository.findById(c.getVenueId()).orElseThrow(), notice, artistIds, bookers);
        log.info("concert_notice_created concertId={} noticeId={} kind={} artists={} bookers={}", concertId, notice.getId(),
                notice.getKind(), artistIds.size(), bookers.size());
        return NoticeView.of(notice);
    }

    @Transactional
    public NoticeView update(long concertId, long noticeId, NoticeUpdateRequest r) {
        ConcertNoticeEntity notice = notice(concertId, noticeId);
        notice.edit(r.kind(), r.title() == null ? null : blank(r.title()), r.body() == null ? null : blank(r.body()),
                OffsetDateTime.now(clock));
        return NoticeView.of(notices.save(notice));
    }

    @Transactional
    public void delete(long concertId, long noticeId) {
        notices.delete(notice(concertId, noticeId));
        log.info("concert_notice_deleted concertId={} noticeId={}", concertId, noticeId);
    }

    private ConcertEntity concert(long id) {
        return concertRepository.findById(id).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
    }

    private ConcertNoticeEntity notice(long concertId, long noticeId) {
        return notices.findByIdAndConcertId(noticeId, concertId).orElseThrow(() -> new GeneralException(CommonErrorCode.NOT_FOUND));
    }

    private static String blank(String s) {
        String stripped = s.strip();
        if (stripped.isEmpty()) throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "제목 · 내용은 비울 수 없습니다");
        return stripped;
    }
}
