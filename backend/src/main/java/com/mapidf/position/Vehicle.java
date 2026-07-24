package com.mapidf.position;

import java.time.Instant;

public record Vehicle(String tripId, double lat, double lng, double bearing,
                      String status, String headsign, String nextStop, Instant expectedTime, Source source) {

    public enum Source {
        REALTIME, INTERPOLATED
    }
}
