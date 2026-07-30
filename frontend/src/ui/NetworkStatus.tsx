import type { NetworkStatus as Status } from "../map/useNetwork";

interface Props {
  status: Status;
}

/**
 * Bandeau des états où la carte n'a rien à montrer. Sans lui, le premier démarrage (les données
 * de transport se chargent encore) et une API éteinte donnent le même écran blanc muet.
 *
 * Formulations volontairement non techniques — ni « backend », ni « GTFS », ni code HTTP : le
 * message s'adresse à quelqu'un qui veut voir passer son métro. Le détail d'un échec part en
 * console (cf. useNetwork), où il sert à qui sait le lire.
 */
export function NetworkStatus({ status }: Props) {
  if (status === "ready") {
    return null;
  }
  const message = {
    loading: { title: "Chargement du plan…", body: null as string | null },
    empty: {
      title: "Plan en préparation",
      body: "Les données de transport sont en cours de chargement — cela peut prendre "
        + "quelques minutes. La carte s'affichera d'elle-même, sans rien recharger.",
    },
    error: {
      title: "Données momentanément indisponibles",
      body: "La connexion au service ne répond pas. Nouvelle tentative automatique toutes "
        + "les 10 secondes.",
    },
  }[status];
  return (
    <div
      style={{
        position: "absolute",
        top: 12,
        left: "50%",
        transform: "translateX(-50%)",
        maxWidth: 360,
        padding: "10px 14px",
        background: "#fff",
        borderRadius: 8,
        borderLeft: `4px solid ${status === "error" ? "#b45309" : "#1d4ed8"}`,
        boxShadow: "0 2px 12px rgba(0,0,0,.2)",
        font: "13px sans-serif",
        // La carte reste manipulable sous le bandeau (pas de bouton dedans).
        pointerEvents: "none",
      }}
      role="status"
    >
      <b>{message.title}</b>
      {message.body && <div style={{ color: "#444", marginTop: 4 }}>{message.body}</div>}
    </div>
  );
}
