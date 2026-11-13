package com.indie.concert.domain.concert.entity;

/**
 * 장르 코드표 — 🔴 Catalog 의 Genre 와 같은 목록입니다 (2026-11-13 결정: 장르는 Catalog 것을 씁니다).
 * artist.genre(Catalog artist.updated 복제) · concert.genre · 공연 목록의 genre 필터가 이 값을 씁니다.
 * Catalog 가 값을 넣거나 빼면 여기와 V2__concert_values.sql 의 ck_artist_genre · ck_concert_genre 를 함께 고칩니다 (ConcertSchemaTest 가 대조).
 */
public enum Genre {
    INDIE_ROCK, ALTERNATIVE, GARAGE, PUNK, HARDCORE, EMO, METAL, POST_ROCK, SHOEGAZE, PSYCHEDELIC,
    INDIE_POP, DREAM_POP, SYNTH_POP, CITY_POP,
    FOLK, ELECTRONIC, RNB_SOUL, HIPHOP, JAZZ, BLUES, FUNK, SKA_REGGAE, ETC;

    /** 목록에 없는 값이면 null */
    public static Genre parse(String code) {
        if (code == null) return null;
        try {
            return valueOf(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
