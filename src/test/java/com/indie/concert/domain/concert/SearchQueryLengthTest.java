package com.indie.concert.domain.concert;

import com.indie.concert.domain.concert.controller.ConcertController;
import com.indie.concert.domain.concert.service.ConcertNoticeService;
import com.indie.concert.domain.concert.service.ConcertService;
import com.indie.concert.domain.concert.service.VenueService;
import com.indie.concert.domain.follow.controller.FollowingController;
import com.indie.concert.domain.follow.service.FollowingService;
import com.indie.concert.global.auth.CurrentUserArgumentResolver;
import com.indie.concert.global.common.exception.GlobalExceptionHandler;
import com.indie.concert.global.common.util.PageSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 11/23 검색어 최대 100자 — 붙여넣기까지 생각해 지금 가장 긴 이름(공연 제목 62자)보다 넉넉하게. 넘으면 조회 없이 400 */
class SearchQueryLengthTest {

    private final ConcertService concertService = mock(ConcertService.class);
    private final VenueService venueService = mock(VenueService.class);
    private final FollowingService followingService = mock(FollowingService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        PageSize pageSize = mock(PageSize.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new ConcertController(concertService, venueService, mock(ConcertNoticeService.class), pageSize),
                        new FollowingController(followingService, pageSize))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void overLimitIsRejected() throws Exception {
        String over = "가".repeat(101);
        for (String path : List.of("/api/concerts", "/api/concerts/search", "/api/concerts/venues", "/api/concerts/following")) {
            mvc.perform(get(path).header(CurrentUserArgumentResolver.HEADER, "1").param("q", over))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(concertService, venueService, followingService);
    }

    @Test
    void limitItselfPasses() throws Exception {
        mvc.perform(get("/api/concerts/search").param("q", "가".repeat(100))).andExpect(status().isOk());
    }
}
