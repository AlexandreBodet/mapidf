package com.mapidf.network;

import java.util.List;

import com.mapidf.position.StopOnLine;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import static org.assertj.core.api.Assertions.assertThat;

class LineBranchTest {

    private static final GeometryFactory GF = new GeometryFactory(new PrecisionModel(), 4326);

    private static LineString line() {
        return GF.createLineString(new Coordinate[]{
            new Coordinate(2.300, 48.850), new Coordinate(2.320, 48.850)});
    }

    @Test
    void looksUpStopIndexInConstantTime() {
        LineBranch branch = LineBranch.of("SH9", (short) 0, "Gamma", line(), List.of(
            new StopOnLine("1", "Alpha", 0.0, 0),
            new StopOnLine("2", "Beta", 0.01, 300),
            new StopOnLine("3", "Gamma", 0.02, 600)));

        assertThat(branch.indexOf("1")).isZero();
        assertThat(branch.indexOf("2")).isEqualTo(1);
        assertThat(branch.indexOf("3")).isEqualTo(2);
    }

    @Test
    void returnsMinusOneForAStopOutsideTheBranch() {
        LineBranch branch = LineBranch.of("SH9", (short) 0, "Gamma", line(), List.of(
            new StopOnLine("1", "Alpha", 0.0, 0)));

        // Garantit qu'un train de branche non couverte est écarté proprement au lieu de lever,
        // et qu'il peut donc être compté dans une métrique.
        assertThat(branch.indexOf("999")).isEqualTo(-1);
    }

    @Test
    void buildsAnIndexedLineFromTheGeometry() {
        LineBranch branch = LineBranch.of("SH9", (short) 0, "Gamma", line(), List.of());

        // Préconstruit une fois pour toutes : /vehicles le rebâtissait à chaque requête.
        assertThat(branch.indexed()).isNotNull();
        assertThat(branch.indexed().getEndIndex()).isGreaterThan(0.0);
    }
}
