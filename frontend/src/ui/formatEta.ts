/**
 * Sans `withSeconds`, la forme est celle d'un afficheur de quai (« 3 min ») : c'est le format des
 * listes, où trois horaires doivent tenir côte à côte sur les 260 px d'une fiche station. La forme
 * en phrase reste pour la fiche d'un train, qui n'en montre qu'un.
 */
export function formatEta(expectedTime: string, opts: { withSeconds?: boolean } = {}): string {
  const sec = Math.round((new Date(expectedTime).getTime() - Date.now()) / 1000);
  if (Number.isNaN(sec)) {
    return "—";
  }
  if (sec <= 0) {
    return opts.withSeconds ? "imminent / à quai" : "imminent";
  }
  if (sec < 60) {
    return opts.withSeconds ? `dans ${sec} s` : "imminent";
  }
  const min = Math.floor(sec / 60);
  return opts.withSeconds ? `dans ${min} min ${sec % 60} s` : `${min} min`;
}
