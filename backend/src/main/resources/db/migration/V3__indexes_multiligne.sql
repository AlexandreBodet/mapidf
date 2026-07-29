-- Index préparant le multi-ligne (coût nul en mono-ligne).
-- parent_station : la résolution station → quais se fait désormais en mémoire, au build du
-- registry (NetworkRegistryBuilder), plus par requête. L'index est conservé : il ne coûte rien
-- à ces volumes et redeviendrait utile si un jour on requêtait par station parente.
-- stop_id : FK de stop_time non indexée en V1 ; utile dès qu'on requête par arrêt.
CREATE INDEX idx_stop_parent_station ON stop (parent_station);
CREATE INDEX idx_stop_time_stop ON stop_time (stop_id);
