import { useEffect, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { searchStations } from "../api/network";
import { SEARCH_DEBOUNCE_MS } from "../api/config";
import type { NetworkStation } from "../api/types";
import styles from "./StationSearch.module.css";

interface Props {
  onSelectStation: (id: string, coords: [number, number]) => void;
}

/**
 * Recherche de station : point d'entrée clavier pour atteindre une entité de la carte sans souris
 * (UX-5a, dette héritée d'UX-4 — un canevas MapLibre n'a pas d'enfants focusables). Pattern ARIA
 * combobox/listbox : le focus DOM reste sur l'input du début à la fin, aucun résultat n'est
 * focusable directement — la sélection se pilote par `aria-activedescendant`.
 */
export function StationSearch({ onSelectStation }: Props) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<NetworkStation[]>([]);
  const [activeIndex, setActiveIndex] = useState(-1);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    const trimmed = query.trim();
    const timer = window.setTimeout(() => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;
      if (!trimmed) {
        setResults([]);
        setActiveIndex(-1);
        return;
      }
      searchStations(trimmed, controller.signal)
        .then((response) => {
          if (!controller.signal.aborted) {
            setResults(response.results);
            setActiveIndex(-1);
          }
        })
        .catch(() => {
          if (!controller.signal.aborted) {
            setResults([]);
          }
        });
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [query]);

  const open = results.length > 0;

  const select = (station: NetworkStation) => {
    onSelectStation(station.id, [station.lng, station.lat]);
    setQuery("");
    setResults([]);
    setActiveIndex(-1);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown" && open) {
      e.preventDefault();
      setActiveIndex((i) => Math.min(i + 1, results.length - 1));
    } else if (e.key === "ArrowUp" && open) {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === "Enter" && open && activeIndex >= 0) {
      e.preventDefault();
      select(results[activeIndex]);
    } else if (e.key === "Escape" && (open || query)) {
      // Empêche l'Échap global de l'app (fermeture de fiche, App.tsx) de réagir à un geste qui ne
      // visait que la recherche : les deux panneaux sont indépendants sur desktop.
      e.stopPropagation();
      setQuery("");
      setResults([]);
      setActiveIndex(-1);
    }
  };

  const activeId = activeIndex >= 0 ? `station-option-${results[activeIndex].id}` : undefined;

  return (
    <div className={styles.search}>
      <input
        type="text"
        className={styles.input}
        role="combobox"
        aria-expanded={open}
        aria-controls="station-search-listbox"
        aria-activedescendant={activeId}
        aria-autocomplete="list"
        aria-label="Rechercher une station"
        placeholder="Rechercher une station…"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={onKeyDown}
      />
      <p className={styles.srOnly} aria-live="polite">
        {open ? `${results.length} résultat${results.length > 1 ? "s" : ""}` : ""}
      </p>
      {/* Toujours montée, masquée par `hidden` : convention du projet (index.css porte la garde
          `[hidden] { display: none !important }`), et ça évite un `aria-controls` qui pointerait
          par moments vers un id absent du DOM. */}
      <ul className={styles.results} role="listbox" id="station-search-listbox" hidden={!open}>
        {results.map((station, index) => (
          <li
            key={station.id}
            id={`station-option-${station.id}`}
            role="option"
            aria-selected={index === activeIndex}
            className={styles.result}
            onMouseEnter={() => setActiveIndex(index)}
            onClick={() => select(station)}
          >
            {station.name}
          </li>
        ))}
      </ul>
    </div>
  );
}
