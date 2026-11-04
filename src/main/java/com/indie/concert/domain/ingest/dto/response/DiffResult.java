package com.indie.concert.domain.ingest.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record DiffResult(@JsonProperty("new") List<String> created, List<String> changed, List<String> unchanged) {}
