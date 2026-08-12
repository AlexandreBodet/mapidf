import { formatEta } from "./formatEta";
import { statusLabel } from "./status";
import type { Vehicle } from "../api/types";
import styles from "./VehiclePanel.module.css";

interface Props {
  // Vehicle complet, obtenu via la Map<journeyRef, Vehicle> tenue par useVehicles : la
  // feature MapLibre cliquée ne porte plus headsign/nextStop/expectedTime/status depuis la
  // tâche 14 (allègement de la boucle de rendu), donc le panneau ne peut plus les lire
  // depuis les propriétés de la feature.
  vehicle: Vehicle;
  following?: boolean;
  onFollow?: () => void;
}

export function VehiclePanel({ vehicle, following = false, onFollow }: Props) {
  return (
    <>
      <p className={styles.line}>
        Prochain arrêt : <b>{vehicle.nextStop}</b>
      </p>
      <p className={styles.line}>
        Arrivée estimée : <b>{formatEta(vehicle.expectedTime, { withSeconds: true })}</b>
      </p>
      <p className={styles.line}>État : {statusLabel(vehicle.status)}</p>
      {/* Le métro n'a pas de GPS : la position est TOUJOURS estimée par interpolation, jamais
          mesurée. Le backend ne peut produire aucun autre cas. */}
      <p className={styles.muted}>Position : estimée (horaire)</p>
      {vehicle.confidence === "APPROXIMATE" && (
        <p className={styles.approx}>
          Position approximative : le flux temps réel n'annonce qu'un seul arrêt pour ce train.
        </p>
      )}
      {vehicle.recordedAt && (
        <p className={styles.muted}>
          Donnée du {new Date(vehicle.recordedAt).toLocaleTimeString("fr-FR")}
        </p>
      )}
      <button onClick={onFollow} className={styles.follow} aria-pressed={following}>
        {following ? "◉ Suivi actif" : "◉ Suivre"}
      </button>
    </>
  );
}
