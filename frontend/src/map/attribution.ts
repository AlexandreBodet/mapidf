/**
 * OBLIGATION DE LICENCE — ne pas retirer. Les données IDFM (GTFS statique et temps réel SIRI)
 * sont sous « Licence Mobilité » : son article 5.4 impose, dès que la carte est utilisée
 * publiquement, une mention informant l'utilisateur que le contenu vient de la base initiale et
 * qu'il est soumis à la licence — avec lien vers les deux. Nommée et isolée ici pour rester
 * trouvable au lieu d'être noyée dans la construction de la carte.
 *
 * L'attribution du fond de carte (OpenFreeMap / OpenMapTiles / OpenStreetMap) est, elle,
 * fournie par la TileJSON de la source et posée automatiquement par MapLibre.
 */
export const SOURCE_ATTRIBUTION =
  "Contient des informations de " +
  '<a href="https://transport.data.gouv.fr/datasets/reseau-urbain-et-interurbain-dile-de-france-mobilites"' +
  ' target="_blank" rel="noreferrer">Réseaux urbains et interurbains d\'Île-de-France Mobilités</a>' +
  ", mises à disposition aux conditions de la " +
  '<a href="https://cloud.fabmob.io/s/eYWWJBdM3fQiFNm" target="_blank" rel="noreferrer">Licence Mobilités</a>';

/**
 * OBLIGATION DE LICENCE — art. 5.7 de la Licence Mobilité (« neutralité et loyauté ») : ne pas
 * induire en erreur sur le contenu. En mode étroit cette phrase rejoint la mention de source
 * derrière le « ⓘ », pour que la feuille puisse se replier à la seule poignée sans la faire
 * disparaître. Au-dessus du seuil, elle reste dans le pied du panneau (`SheetFooter`).
 */
export const ESTIMATION_NOTICE =
  "Positions estimées d'après les horaires temps réel : le métro n'a pas de GPS.";
