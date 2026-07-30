import type { NetworkStatus as Status } from "../map/useNetwork";

interface Props {
  status: Status;
  /** Message technique de l'échec (`/network 502`, « Failed to fetch »…), affiché tel quel. */
  detail: string | null;
}

/**
 * Bandeau des états où la carte n'a rien à montrer. Sans lui, le premier démarrage (le backend
 * charge le GTFS et sert un réseau vide) et un backend éteint donnent le même écran blanc muet.
 */
export function NetworkStatus({ status, detail }: Props) {
  if (status === "ready") {
    return null;
  }
  const message = {
    loading: { title: "Chargement du réseau…", body: null as string | null },
    empty: {
      title: "Réseau pas encore prêt",
      body: "Au premier démarrage, le backend télécharge et charge le GTFS d'Île-de-France "
        + "Mobilités (~109 Mo). La carte se remplira dès que ce sera fini — nouvelle "
        + "vérification toutes les 10 s.",
    },
    error: {
      title: "Backend injoignable",
      body: "Aucune réponse de l'API. Nouvelle tentative toutes les 10 s.",
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
      {detail && <div style={{ color: "#888", marginTop: 4, font: "12px monospace" }}>{detail}</div>}
    </div>
  );
}
