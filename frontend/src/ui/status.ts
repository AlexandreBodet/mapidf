export type StatusKind = "onTime" | "delayed" | "early" | "cancelled" | "unknown";

// PRIM émet du SCREAMING_SNAKE ; seuls ON_TIME et DELAYED ont été mesurés sur le métro (cf.
// prim-integration.md), les autres valeurs SIRI sont admises par prudence. Une valeur inconnue
// tombe sur "unknown" plutôt que d'être affichée telle quelle.
const KINDS: Record<string, StatusKind> = {
  ON_TIME: "onTime",
  ONTIME: "onTime",
  DELAYED: "delayed",
  EARLY: "early",
  CANCELLED: "cancelled",
  CANCELED: "cancelled",
};

const LABELS: Record<StatusKind, string> = {
  onTime: "à l'heure",
  delayed: "retardé",
  early: "en avance",
  cancelled: "supprimé",
  unknown: "—",
};

export function statusKind(status: string | null | undefined): StatusKind {
  return KINDS[(status ?? "").toUpperCase()] ?? "unknown";
}

export function statusLabel(status: string | null | undefined): string {
  return LABELS[statusKind(status)];
}
