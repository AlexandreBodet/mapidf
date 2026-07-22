package com.mapidf.rt;

import java.nio.charset.StandardCharsets;

final class RtFixtures {

    private RtFixtures() {
    }

    // Une course : prochain arrêt "STIF:StopPoint:Q:2:", ETA 14:05:00Z, destination "Gamma", direction "0"
    static byte[] siriLineNineSample() {
        String json = """
            {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-22T14:00:00.000Z",
              "EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
                "EstimatedVehicleJourney":[{
                  "LineRef":{"value":"STIF:Line::C01379:"},
                  "DirectionRef":{"value":"0"},
                  "DatedVehicleJourneyRef":{"value":"J1"},
                  "DestinationName":[{"value":"Gamma"}],
                  "EstimatedCalls":{"EstimatedCall":[{
                    "StopPointRef":{"value":"STIF:StopPoint:Q:2:"},
                    "ExpectedDepartureTime":"2026-07-22T14:05:00.000Z",
                    "DestinationDisplay":[{"value":"Gamma"}],
                    "DepartureStatus":"ON_TIME"
                  }]}
                }]
              }]}]
            }}}
            """;
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
