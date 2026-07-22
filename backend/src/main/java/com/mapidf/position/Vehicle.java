package com.mapidf.position;

public record Vehicle(String tripId, double lat, double lng, double bearing,
                      int delaySec, String headsign, String nextStop, Source source) {

    public enum Source {
        REALTIME, INTERPOLATED
    }
}
