import { useEffect, useState } from "react";
import { fetchDisruptions } from "./network";
import type { LineDisruptions, Severity } from "./types";

// Une perturbation vit des heures ou des semaines, et le backend ne rafraîchit son instantané
// que toutes les 5 min. Une minute suffit donc largement côté client — l'intérêt de ce rythme
// est surtout que la FIN d'une perturbation se voie sans recharger la page.
const POLL_MS = 60_000;

export interface Disruptions {
  byLine: Map<string, LineDisruptions>;
  /** Gravité la plus élevée par station, pour l'anneau sur la carte. */
  stationSeverity: Map<string, Severity>;
}

const NOTHING: Disruptions = { byLine: new Map(), stationSeverity: new Map() };

/** Perturbations en cours. Tout est vide tant que rien n'a été reçu. */
export function useDisruptions(): Disruptions {
  const [disruptions, setDisruptions] = useState<Disruptions>(NOTHING);

  useEffect(() => {
    let cancelled = false;
    let timer = 0;
    const tick = async () => {
      try {
        const fresh = await fetchDisruptions();
        if (!cancelled) {
          setDisruptions({
            byLine: new Map(fresh.lines.map((line) => [line.lineId, line])),
            stationSeverity: new Map(fresh.stations.map((s) => [s.stationId, s.severity])),
          });
        }
      } catch {
        // On garde le dernier état connu : l'indicateur de connexion perdue du LinePicker
        // couvre déjà le cas d'un backend muet, inutile d'un second signal.
      }
      if (!cancelled) {
        timer = window.setTimeout(tick, POLL_MS);
      }
    };
    tick();
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, []);

  return disruptions;
}
