/**
 * État de sélection partageable par lien (UX-5b) : station ouverte, train suivi, filtre de
 * lignes visibles. Module pur, sans dépendance à React ni au DOM (hormis `URLSearchParams`,
 * fourni par l'environnement d'exécution) — la validation sémantique (un id existe-t-il vraiment
 * dans le réseau courant ?) vit dans App.tsx, pas ici : ce module ne connaît que la syntaxe de
 * l'URL.
 */
export interface PermalinkState {
  stationId: string | null;
  journeyRef: string | null;
  /** `null` = toutes les lignes. Jamais un tableau vide : cf. ui/toggleLine.ts. */
  visibleLineIds: string[] | null;
}

export function encodePermalink(state: PermalinkState): string {
  const params = new URLSearchParams();
  if (state.stationId) {
    params.set("station", state.stationId);
  } else if (state.journeyRef) {
    params.set("train", state.journeyRef);
  }
  if (state.visibleLineIds && state.visibleLineIds.length > 0) {
    params.set("lines", state.visibleLineIds.join(","));
  }
  const query = params.toString();
  return query ? `?${query}` : "";
}

export function decodePermalink(search: string): PermalinkState {
  const params = new URLSearchParams(search);
  const stationId = params.get("station");
  const linesParam = params.get("lines");
  const visibleLineIds = linesParam ? linesParam.split(",").filter(Boolean) : [];
  return {
    stationId,
    // Un lien composé à la main peut porter les deux : la station l'emporte, au même titre
    // qu'un clic carte ferme le suivi d'un train (App.tsx, selectStation).
    journeyRef: stationId ? null : params.get("train"),
    visibleLineIds: visibleLineIds.length > 0 ? visibleLineIds : null,
  };
}
