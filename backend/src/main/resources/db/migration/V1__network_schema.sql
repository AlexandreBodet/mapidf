CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE route (
    id          UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    gtfs_id     TEXT NOT NULL UNIQUE,
    short_name  TEXT NOT NULL,
    color       TEXT,
    geom        geometry(LineString, 4326) NOT NULL
);

CREATE TABLE stop (
    id       UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    gtfs_id  TEXT NOT NULL UNIQUE,
    name     TEXT NOT NULL,
    geom     geometry(Point, 4326) NOT NULL
);

CREATE TABLE trip (
    id        UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    gtfs_id   TEXT NOT NULL UNIQUE,
    route_id  UUID NOT NULL REFERENCES route(id),
    headsign  TEXT,
    direction SMALLINT
);

CREATE TABLE stop_time (
    id             UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    trip_id        UUID NOT NULL REFERENCES trip(id),
    stop_id        UUID NOT NULL REFERENCES stop(id),
    stop_sequence  INT  NOT NULL,
    arrival_sec    INT  NOT NULL,
    departure_sec  INT  NOT NULL,
    UNIQUE (trip_id, stop_sequence)
);

CREATE INDEX idx_trip_route ON trip(route_id);
CREATE INDEX idx_stop_time_trip ON stop_time(trip_id);
