-- Index préparant le multi-ligne (coût nul en mono-ligne).
-- parent_station : utilisé par StopRepository.findByParentStation (résolution station /departures).
-- stop_id : FK de stop_time non indexée en V1 ; utile dès qu'on requête par arrêt.
CREATE INDEX idx_stop_parent_station ON stop (parent_station);
CREATE INDEX idx_stop_time_stop ON stop_time (stop_id);
