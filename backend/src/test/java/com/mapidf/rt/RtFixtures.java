package com.mapidf.rt;

import java.nio.charset.StandardCharsets;

final class RtFixtures {

    private RtFixtures() {
    }

    static java.io.InputStream stream(byte[] json) {
        return new java.io.ByteArrayInputStream(json);
    }

    /** Corps gzippé, comme PRIM le renvoie quand on envoie Accept-Encoding: gzip. */
    static byte[] gzip(byte[] raw) throws java.io.IOException {
        var out = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(raw);
        }
        return out.toByteArray();
    }

    // Course ligne 9 portant un RecordedAtTime plus vieux que la réponse (cas mesuré : 9 min
    // d'écart sur une course à un seul appel).
    static byte[] siriStaleJourneySample() {
        String json = """
            {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-22T14:00:00.000Z",
              "EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
                "EstimatedVehicleJourney":[{
                  "RecordedAtTime":"2026-07-22T13:51:00.000Z",
                  "LineRef":{"value":"STIF:Line::C01379:"},
                  "DirectionRef":{"value":"0"},
                  "DatedVehicleJourneyRef":{"value":"J1"},
                  "DestinationName":[{"value":"Gamma"}],
                  "EstimatedCalls":{"EstimatedCall":[{
                    "StopPointRef":{"value":"STIF:StopPoint:Q:2:"},
                    "ExpectedDepartureTime":"2026-07-22T14:05:00.000Z",
                    "DepartureStatus":"ON_TIME"
                  }]}
                }]
              }]}]
            }}}
            """;
        return json.getBytes(StandardCharsets.UTF_8);
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

    // Une course ligne 9 dont les EstimatedCall NE sont PAS triés (cas réel constaté sur PRIM).
    // Réf. asOf des tests = 14:00:00Z. Appels : un passé (13:58, Q:1), le vrai prochain (14:02, Q:2),
    // puis des arrêts plus lointains dans un ordre quelconque (14:06 Q:5 en tête, 14:10 Q:8).
    // Le prochain arrêt attendu est Q:2 (le plus tôt encore à venir), PAS Q:5 (le premier du tableau).
    static byte[] siriUnorderedCallsSample() {
        String json = """
            {"Siri":{"ServiceDelivery":{"ResponseTimestamp":"2026-07-22T14:00:00.000Z",
              "EstimatedTimetableDelivery":[{"EstimatedJourneyVersionFrame":[{
                "EstimatedVehicleJourney":[{
                  "LineRef":{"value":"STIF:Line::C01379:"},
                  "DirectionRef":{"value":"0"},
                  "DatedVehicleJourneyRef":{"value":"J1"},
                  "DestinationName":[{"value":"Gamma"}],
                  "EstimatedCalls":{"EstimatedCall":[
                    {"StopPointRef":{"value":"STIF:StopPoint:Q:5:"},
                     "ExpectedArrivalTime":"2026-07-22T14:06:00.000Z","DepartureStatus":"DELAYED"},
                    {"StopPointRef":{"value":"STIF:StopPoint:Q:2:"},
                     "ExpectedArrivalTime":"2026-07-22T14:02:00.000Z","DepartureStatus":"ON_TIME"},
                    {"StopPointRef":{"value":"STIF:StopPoint:Q:1:"},
                     "ExpectedArrivalTime":"2026-07-22T13:58:00.000Z","DepartureStatus":"ON_TIME"},
                    {"StopPointRef":{"value":"STIF:StopPoint:Q:8:"},
                     "ExpectedArrivalTime":"2026-07-22T14:10:00.000Z","DepartureStatus":"ON_TIME"}
                  ]}
                }]
              }]}]
            }}}
            """;
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
