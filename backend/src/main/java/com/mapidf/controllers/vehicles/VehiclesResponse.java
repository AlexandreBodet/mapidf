package com.mapidf.controllers.vehicles;

import java.time.Instant;
import java.util.List;

import com.mapidf.position.Vehicle;

public record VehiclesResponse(Instant asOf, List<VehicleDto> vehicles) {

    /**
     * @param recordedAt  dernière mise à jour de la course côté SIRI (information d'affichage)
     * @param confidence  RELIABLE ou APPROXIMATE — fiabilité du placement, sur un signal
     *                    structurel. Le front atténue les APPROXIMATE sans jamais les masquer.
     */
    public record VehicleDto(String journeyRef, String lineId, double lat, double lng,
                             double bearing, String status, String headsign, String nextStop,
                             Instant expectedTime, Instant recordedAt,
                             String source, String confidence) {

        public static VehicleDto from(Vehicle vehicle) {
            return new VehicleDto(vehicle.journeyRef(), vehicle.lineId(), vehicle.lat(),
                vehicle.lng(), vehicle.bearing(), vehicle.status(), vehicle.headsign(),
                vehicle.nextStop(), vehicle.expectedTime(), vehicle.recordedAt(),
                vehicle.source().name(), vehicle.confidence().name());
        }
    }
}
