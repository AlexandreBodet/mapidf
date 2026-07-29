package com.mapidf.position;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Réduit aux primitives encore actives : le calcul de position est neutralisé le temps de la
 * bascule vers les branches, la tâche 9 reconstruit sa couverture.
 */
class PositionEngineTest {

    @Test
    void stopKeyExtractsLastNumericGroup() {
        assertThat(PositionEngine.stopKey("STIF:StopPoint:Q:463221:")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey("IDFM:463221")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey("IDFM:StopPoint:59:463221")).isEqualTo("463221");
        assertThat(PositionEngine.stopKey(null)).isEmpty();
        assertThat(PositionEngine.stopKey("aucun-chiffre")).isEmpty();
    }
}
