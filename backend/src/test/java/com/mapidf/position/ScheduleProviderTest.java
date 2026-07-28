package com.mapidf.position;

import java.util.List;

import com.mapidf.data.repositories.StopTimeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScheduleProviderTest {

    @Mock
    StopTimeRepository stopTimeRepository;

    @InjectMocks
    ScheduleProvider provider;

    private static LineString line() {
        return new GeometryFactory().createLineString(
            new Coordinate[]{new Coordinate(2.0, 48.0), new Coordinate(2.1, 48.1)});
    }

    @Test
    void cachesScheduleUntilInvalidated() {
        when(stopTimeRepository.findScheduleByRouteGtfsId("R")).thenReturn(List.of());

        provider.getLineSchedule(line(), "R");
        provider.getLineSchedule(line(), "R");
        verify(stopTimeRepository, times(1)).findScheduleByRouteGtfsId("R");

        provider.invalidate();
        provider.getLineSchedule(line(), "R");
        verify(stopTimeRepository, times(2)).findScheduleByRouteGtfsId("R");
    }
}
