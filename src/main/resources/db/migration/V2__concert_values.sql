-- concert 값 목록 — 컬럼에 들어갈 수 있는 값(Java enum 과 같은 목록)을 CHECK 로 고정합니다. 구조는 V1__concert_schema.sql.
-- 값을 넣거나 빼면 이 파일과 Java enum 을 함께 고칩니다 — ConcertSchemaTest 가 둘을 대조합니다.

-- ── 장르 (Genre) — 🔴 Catalog 의 Genre 와 같은 목록입니다. Catalog 가 값을 바꾸면 여기와 concert 의 Genre 도 함께 고칩니다 ──
ALTER TABLE artist ADD CONSTRAINT ck_artist_genre CHECK (genre IN (
    'INDIE_ROCK','ALTERNATIVE','GARAGE','PUNK','HARDCORE','EMO','METAL','POST_ROCK','SHOEGAZE','PSYCHEDELIC',
    'INDIE_POP','DREAM_POP','SYNTH_POP','CITY_POP',
    'FOLK','ELECTRONIC','RNB_SOUL','HIPHOP','JAZZ','BLUES','FUNK','SKA_REGGAE','ETC'));
ALTER TABLE concert ADD CONSTRAINT ck_concert_genre CHECK (genre IN (
    'INDIE_ROCK','ALTERNATIVE','GARAGE','PUNK','HARDCORE','EMO','METAL','POST_ROCK','SHOEGAZE','PSYCHEDELIC',
    'INDIE_POP','DREAM_POP','SYNTH_POP','CITY_POP',
    'FOLK','ELECTRONIC','RNB_SOUL','HIPHOP','JAZZ','BLUES','FUNK','SKA_REGGAE','ETC'));

-- ── 공연장 · 공연 (Region · ConcertEntity.Type) ──
ALTER TABLE venue ADD CONSTRAINT ck_venue_region CHECK (region IN (
    'SEOUL','BUSAN','DAEGU','INCHEON','GWANGJU','DAEJEON','ULSAN','SEJONG',
    'GYEONGGI','GANGWON','CHUNGBUK','CHUNGNAM','JEONBUK','JEONNAM','GYEONGBUK','GYEONGNAM','JEJU'));
ALTER TABLE concert ADD CONSTRAINT ck_concert_type CHECK (type IN ('SOLO','FESTIVAL'));

-- ── 예매 · 좌석 · 대기열 (BookingEntity.Status · ConcertSeatEntity.Status · QueueEntryEntity.Status) ──
ALTER TABLE booking ADD CONSTRAINT ck_booking_status CHECK (status IN ('PENDING','CONFIRMED','CANCELLED','EXPIRED'));
ALTER TABLE concert_seat ADD CONSTRAINT ck_concert_seat_status CHECK (status IN ('AVAILABLE','HELD','PAYING','SOLD'));
ALTER TABLE queue_entry ADD CONSTRAINT ck_queue_entry_status CHECK (status IN ('WAITING','ADMITTED','LEFT'));
