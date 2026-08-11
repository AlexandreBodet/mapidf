#!/usr/bin/env bash
# Garde-fou des en-têtes de sécurité (chantier SEC-4). Interroge une pile lancée et sort en
# erreur si un en-tête manque ou a changé de valeur.
#
#   scripts/check-headers.sh [base-url]        # défaut : http://localhost:8080
#
# Les valeurs attendues sont dupliquées ici : c'est un test, pas une source. Si la conf nginx
# change délibérément, ce fichier change avec elle — c'est précisément l'intérêt.
set -uo pipefail

BASE="${1:-http://localhost:8080}"
failures=0

CSP="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; style-src-elem 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data: blob: https://tiles.openfreemap.org; connect-src 'self' https://tiles.openfreemap.org; child-src 'self' blob:; worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'; object-src 'none'"

# Corps ignoré : seuls les en-têtes comptent. Noms ramenés en minuscules pour comparer sans
# dépendre de la casse choisie par le serveur.
headers_of() {
  curl -sS -o /dev/null -D - "$1" | tr -d '\r' \
    | awk 'NR>1 && /:/ {
        name = tolower(substr($0, 1, index($0, ":") - 1));
        value = substr($0, index($0, ":") + 2);
        print name "\t" value
      }'
}

expect() { # fichier nom valeur_attendue
  local actual
  actual="$(awk -F'\t' -v n="$2" '$1 == n { print $2 }' "$1")"
  if [[ "$actual" == "$3" ]]; then
    printf '  ✓ %s\n' "$2"
  else
    printf '  ✗ %s\n      attendu : %s\n      obtenu  : %s\n' "$2" "$3" "${actual:-<absent>}"
    failures=$((failures + 1))
  fi
}

reject() { # fichier motif libellé
  if grep -qiE "$2" "$1"; then
    printf '  ✗ %s : %s\n' "$3" "$(grep -iE "$2" "$1" | head -1)"
    failures=$((failures + 1))
  else
    printf '  ✓ %s\n' "$3"
  fi
}

security_headers() { # fichier
  expect "$1" content-security-policy "$CSP"
  expect "$1" x-content-type-options "nosniff"
  expect "$1" x-frame-options "DENY"
  expect "$1" referrer-policy "no-referrer"
  expect "$1" permissions-policy "geolocation=(), camera=(), microphone=()"
  expect "$1" cross-origin-opener-policy "same-origin"
  expect "$1" strict-transport-security "max-age=31536000"
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

echo "→ $BASE/ (page d'entrée)"
headers_of "$BASE/" > "$tmp/root"
security_headers "$tmp/root"
expect "$tmp/root" cache-control "no-cache"
# `server_tokens off` laisse « nginx » mais retire la version : c'est ce chiffre qu'on refuse.
# Le motif n'attend pas de « : » — headers_of sépare le nom de la valeur par une tabulation.
reject "$tmp/root" '^server.*nginx/[0-9]' "la version de nginx n'est pas annoncée"

# Le nom des assets est haché par Vite : on le lit dans la page plutôt que de le figer ici.
asset="$(curl -sS "$BASE/" | grep -o '/assets/[^"]*\.js' | head -1)"
if [[ -z "$asset" ]]; then
  echo "  ✗ aucun /assets/*.js trouvé dans la page : le front est-il bien bâti ?"
  failures=$((failures + 1))
else
  echo "→ $asset (asset haché)"
  headers_of "$BASE$asset" > "$tmp/asset"
  security_headers "$tmp/asset"
  expect "$tmp/asset" cache-control "public, max-age=31536000, immutable"
fi

# Un asset absent doit porter les en-têtes malgré son 404 : c'est ce que `always` garantit, et
# c'est le cas qu'on oublie toujours de vérifier.
echo "→ /assets/absent-de-toute-facon.js (404)"
headers_of "$BASE/assets/absent-de-toute-facon.js" > "$tmp/missing"
security_headers "$tmp/missing"

# La location la plus tentante à croire inutile (« c'est du JSON, pourquoi une CSP ? »), donc celle
# dont il faut constater que les en-têtes y arrivent — l'include du niveau server suffirait à les
# fournir par héritage, ce qu'aucun contrôle HTTP ne peut distinguer. Passe en 200 comme en 502.
# Pas de Cache-Control ici : c'est le backend qui pose le sien sur /network, jamais nginx.
echo "→ $BASE/api/network (proxy backend)"
headers_of "$BASE/api/network" > "$tmp/api"
security_headers "$tmp/api"

if (( failures > 0 )); then
  printf '\n%d écart(s). La conf nginx et ce script ne disent pas la même chose.\n' "$failures"
  exit 1
fi
printf '\nTous les en-têtes attendus sont là.\n'
