package com.mapidf.position;

public record StopOnLine(String stopKey, String stopName, double distanceAlongLine, int scheduledSec) {
}
