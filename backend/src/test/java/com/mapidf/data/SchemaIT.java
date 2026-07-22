package com.mapidf.data;

import com.mapidf.MapIdfTest;
import com.mapidf.data.repositories.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;

@MapIdfTest
class SchemaIT {

    @Autowired
    RouteRepository routeRepository;

    @Test
    void schemaValidatesAndRepositoryWorks() {
        assertThat(routeRepository.findByGtfsId("UNKNOWN")).isEmpty();
    }
}
