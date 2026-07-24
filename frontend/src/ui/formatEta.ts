export function formatEta(expectedTime: string, opts: { withSeconds?: boolean } = {}): string {
  const sec = Math.round((new Date(expectedTime).getTime() - Date.now()) / 1000);
  if (Number.isNaN(sec)) {
    return "—";
  }
  if (sec <= 0) {
    return opts.withSeconds ? "imminent / à quai" : "imminent";
  }
  if (sec < 60) {
    return `dans ${sec} s`;
  }
  const min = Math.floor(sec / 60);
  return opts.withSeconds ? `dans ${min} min ${sec % 60} s` : `dans ${min} min`;
}
