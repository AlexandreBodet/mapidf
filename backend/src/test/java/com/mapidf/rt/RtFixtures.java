package com.mapidf.rt;

import java.nio.charset.StandardCharsets;

final class RtFixtures {

    private RtFixtures() {
    }

    // La 9 est incluse dans le flux multi-lignes ; réutilisé par les tests de résilience.
    static byte[] siriLineNineSample() {
        return siriMultiLineSample();
    }

    // Flux global (estimated-timetable) réduit : deux lignes, la 9 (C01379) + une autre (C01371).
    // Ligne 9 : prochain arrêt "STIF:StopPoint:Q:2:", ETA 14:05:00Z, dest "Gamma", dir "0", course "J1".
    static byte[] siriMultiLineSample() {
        String json = """
            {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-22T14:00:00.000Z",
              "EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
                "EstimatedVehicleJourney":[
                  {
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
                  },
                  {
                    "LineRef":{"value":"STIF:Line::C01371:"},
                    "DirectionRef":{"value":"1"},
                    "DatedVehicleJourneyRef":{"value":"J2"},
                    "DestinationName":[{"value":"Delta"}],
                    "EstimatedCalls":{"EstimatedCall":[{
                      "StopPointRef":{"value":"STIF:StopPoint:Q:9:"},
                      "ExpectedDepartureTime":"2026-07-22T14:07:00.000Z",
                      "DepartureStatus":"ON_TIME"
                    }]}
                  }
                ]
              }]}]
            }}}
            """;
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
