package com.indie.concert.domain.ingest.service;

import com.indie.concert.domain.concert.entity.ConcertEntity;
import com.indie.concert.domain.concert.entity.VenueEntity;
import com.indie.concert.domain.concert.repository.ConcertRepository;
import com.indie.concert.domain.concert.repository.VenueRepository;
import com.indie.concert.domain.ingest.dto.event.ConcertEvents;
import com.indie.concert.domain.ingest.dto.request.ConcertUpsert;
import com.indie.concert.domain.ingest.dto.request.DiffItem;
import com.indie.concert.domain.ingest.dto.request.DiffRequest;
import com.indie.concert.domain.ingest.dto.response.DiffResult;
import com.indie.concert.domain.ingest.dto.response.ItemResult;
import com.indie.concert.global.common.exception.CommonErrorCode;
import com.indie.concert.global.common.exception.GeneralException;
import com.indie.concert.global.common.util.SourceHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 공연 수집 · 시드 적재 (I7 · I15). 남의 스키마에 직접 쓰지 않고 이 API 를 거치므로 이벤트 발행도 여기서 함께 됩니다 (02 ⑤).
 * <ul>
 *   <li>(source, external_id) 기준 멱등 upsert — 같은 요청을 다시 보내도 결과가 같습니다</li>
 *   <li>항목마다 트랜잭션 — 한 건이 실패해도 나머지는 적재되고, 결과에 FAILED 로 표시합니다</li>
 *   <li>공연장은 이름으로 찾아 없으면 만듭니다</li>
 * </ul>
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final ConcertRepository concertRepository;
    private final VenueRepository venueRepository;
    private final ConcertEvents events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public IngestService(ConcertRepository concertRepository, VenueRepository venueRepository, ConcertEvents events,
                         TransactionTemplate tx, Clock clock) {
        this.concertRepository = concertRepository;
        this.venueRepository = venueRepository;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    /** I7 */
    public List<ItemResult> upsertConcerts(List<ConcertUpsert> items) {
        return items.stream().map(item -> eachInTx(item.externalId(), () -> upsertConcert(item))).toList();
    }

    /** artist.updated (Catalog) — 공연에 복제해 둔 아티스트 이름 · 장르를 원본에 맞춥니다. 바뀐 공연 수 */
    public int applyArtist(long artistId, String name, String genre) {
        Integer updated = tx.execute(s -> concertRepository.updateArtist(artistId, name, genre));
        return updated == null ? 0 : updated;
    }

    /** I15 — 수집기는 "뭐가 새로운지"만 묻고, 판정 기준(해시 비교)은 여기 한 곳에만 둡니다 */
    public DiffResult diff(DiffRequest request) {
        List<String> ids = request.items().stream().map(DiffItem::externalId).distinct().toList();
        Map<String, String> stored = new HashMap<>();
        for (SourceHash row : concertRepository.findHashes(request.source(), ids)) stored.put(row.externalId(), row.sourceHash());
        List<String> created = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        for (DiffItem item : request.items()) {
            if (!stored.containsKey(item.externalId())) created.add(item.externalId());
            else if (item.sourceHash() != null && item.sourceHash().equals(stored.get(item.externalId()))) unchanged.add(item.externalId());
            else changed.add(item.externalId());
        }
        return new DiffResult(created, changed, unchanged);
    }

    private ItemResult eachInTx(String externalId, Supplier<ItemResult> work) {
        try {
            return tx.execute(s -> work.get());
        } catch (GeneralException e) {
            return ItemResult.failed(externalId, e.getMessage());
        } catch (DataIntegrityViolationException e) {
            // 같은 external_id · 같은 공연장 이름을 두 요청이 동시에 처음 넣은 경우 — 다시 보내면 UPDATE 경로로 성공합니다
            log.warn("ingest_conflict externalId={} error={}", externalId, e.getMostSpecificCause().getClass().getSimpleName());
            return ItemResult.failed(externalId, "동시 적재 충돌 — 다시 시도하세요");
        }
    }

    private ItemResult upsertConcert(ConcertUpsert u) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ConcertEntity concert = concertRepository.findBySourceAndExternalId(u.source(), u.externalId()).orElse(null);
        if (concert != null && u.sourceHash() != null && Objects.equals(u.sourceHash(), concert.getSourceHash())) {
            return new ItemResult(u.externalId(), concert.getId(), ItemResult.Result.UNCHANGED, null);
        }
        if (u.bookingOpenAt() != null && !u.bookingOpenAt().isBefore(u.startsAt())) {
            throw new GeneralException(CommonErrorCode.INVALID_REQUEST, "booking_open_at 은 starts_at 보다 앞이어야 합니다");
        }
        VenueEntity venue = venueRepository.findByName(u.venue().name().strip())
                .orElseGet(() -> new VenueEntity(u.venue().name().strip()));
        boolean venueChanged = venue.apply(u.venue().region(), u.venue().address(), u.venue().capacity());
        if (venue.getId() == null || venueChanged) venue = venueRepository.save(venue);

        boolean created = concert == null;
        if (created) concert = new ConcertEntity(u.source(), u.externalId());
        boolean changed = concert.apply(u.artistId(), u.artistName().strip(), u.artistGenre(), venue.getId(), u.title().strip(),
                u.type(), u.startsAt(), u.price(), u.bookingOpenAt(), u.queueEnabled(), u.sourceHash(), now);
        concert = concertRepository.save(concert);
        if (created) events.concertCreated(concert, venue);
        return new ItemResult(u.externalId(), concert.getId(), created ? ItemResult.Result.CREATED
                : changed || venueChanged ? ItemResult.Result.UPDATED : ItemResult.Result.UNCHANGED, null);
    }
}
