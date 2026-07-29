-- Une ligne porte désormais N branches (mesuré : 37 tracés sur le métro, dont 2 pour la 7,
-- 2 pour la 13, 2 dans un sens de la 10). Le tracé unique par route rendait la ligne 7
-- fausse de 1547 m. La géométrie migre donc de route vers branch.
--
-- Migration destructrice : les données sont intégralement régénérées au premier refresh
-- GTFS, déclenché au démarrage (initialDelay = 0). Conséquence assumée : une fenêtre de 404
-- entre la migration et la fin de ce premier chargement.

CREATE TABLE branch (
    id                  UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    route_id            UUID NOT NULL REFERENCES route(id),
    gtfs_shape_id       TEXT NOT NULL,
    representative_trip TEXT NOT NULL,
    direction           SMALLINT NOT NULL,
    terminus_name       TEXT,
    geom                geometry(LineString, 4326) NOT NULL,
    UNIQUE (route_id, gtfs_shape_id, direction)
);

-- Le registry se réhydrate depuis la base au démarrage : un redémarrage ne doit pas imposer
-- de retélécharger 109 Mo de GTFS. Il lui faut donc le mode et le LineRef en base.
ALTER TABLE route ADD COLUMN mode TEXT;
ALTER TABLE route ADD COLUMN siri_line_ref TEXT;
ALTER TABLE route DROP COLUMN geom;

-- stop_time s'accroche à la branche, plus à la course.
DELETE FROM stop_time;
ALTER TABLE stop_time DROP CONSTRAINT stop_time_trip_id_stop_sequence_key;
ALTER TABLE stop_time DROP COLUMN trip_id;
ALTER TABLE stop_time ADD COLUMN branch_id UUID NOT NULL REFERENCES branch(id);
ALTER TABLE stop_time ADD CONSTRAINT stop_time_branch_id_stop_sequence_key
    UNIQUE (branch_id, stop_sequence);

DROP TABLE trip;

-- Index d'hygiène de clé étrangère. À ces volumes (37 branches, 915 stop_times) PostgreSQL
-- fait un seq scan et c'est plus rapide qu'un parcours d'index : ils ne sont pas là pour la
-- performance. Aucun index spatial GiST n'est nécessaire — aucune requête spatiale n'est
-- faite, la projection des arrêts sur le tracé s'exécute en Java au build du registry.
CREATE INDEX idx_branch_route ON branch (route_id);
CREATE INDEX idx_stop_time_branch ON stop_time (branch_id);
