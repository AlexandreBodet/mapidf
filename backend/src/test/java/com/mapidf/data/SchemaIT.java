package com.mapidf.data;

import java.util.List;

import com.mapidf.MapIdfTest;
import com.mapidf.data.repositories.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class SchemaIT {

    @Autowired
    RouteRepository routeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void schemaValidatesAndRepositoryWorks() {
        assertThat(routeRepository.findByGtfsId("UNKNOWN")).isEmpty();
    }

    @Test
    void createsMultilineIndexes() {
        List<String> indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indexes).contains("idx_stop_parent_station", "idx_stop_time_stop");
    }
}
