package com.mapidf.data;

import java.util.List;

import com.mapidf.MapIdfTest;
import com.mapidf.data.repositories.BranchRepository;
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
    BranchRepository branchRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void schemaValidatesAndRepositoryWorks() {
        // ddl-auto: validate — ce test échoue si le mapping JPA diverge du schéma Flyway.
        assertThat(routeRepository.findByGtfsId("UNKNOWN")).isEmpty();
        assertThat(branchRepository.findAllWithRoute()).isEmpty();
    }

    @Test
    void createsBranchTableAndDropsTrip() {
        List<String> tables = jdbcTemplate.queryForList(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
        assertThat(tables).contains("branch");
        assertThat(tables).doesNotContain("trip");
    }

    @Test
    void movesGeometryFromRouteToBranch() {
        List<String> routeColumns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'route'",
            String.class);
        assertThat(routeColumns).contains("mode", "siri_line_ref").doesNotContain("geom");

        List<String> branchColumns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'branch'",
            String.class);
        assertThat(branchColumns).contains("geom", "direction", "terminus_name", "gtfs_shape_id");
    }

    @Test
    void repointsStopTimeToBranch() {
        List<String> columns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'stop_time'",
            String.class);
        assertThat(columns).contains("branch_id").doesNotContain("trip_id");
    }

    @Test
    void keepsTheIndexes() {
        List<String> indexes = jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'", String.class);
        assertThat(indexes).contains(
            "idx_stop_parent_station", "idx_stop_time_stop",
            "idx_branch_route", "idx_stop_time_branch");
    }
}
