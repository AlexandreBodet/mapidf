import { describe, expect, it } from "vitest";
import { decodePermalink, encodePermalink } from "./permalink";

describe("encodePermalink", () => {
  it("rend une chaîne vide pour l'état par défaut", () => {
    expect(encodePermalink({ stationId: null, journeyRef: null, visibleLineIds: null })).toBe("");
  });

  it("encode une station seule", () => {
    expect(encodePermalink({ stationId: "ST1", journeyRef: null, visibleLineIds: null }))
      .toBe("?station=ST1");
  });

  it("encode un train seul", () => {
    expect(encodePermalink({ stationId: null, journeyRef: "SIRI:1", visibleLineIds: null }))
      .toBe("?train=SIRI%3A1");
  });

  it("encode le filtre de lignes, plusieurs lignes séparées par une virgule", () => {
    expect(encodePermalink({ stationId: null, journeyRef: null, visibleLineIds: ["1", "4", "9"] }))
      .toBe("?lines=1%2C4%2C9");
  });

  it("station et lignes se combinent dans la même query", () => {
    expect(encodePermalink({ stationId: "ST1", journeyRef: null, visibleLineIds: ["9"] }))
      .toBe("?station=ST1&lines=9");
  });

  it("préfère la station au train si les deux sont fournis", () => {
    expect(encodePermalink({ stationId: "ST1", journeyRef: "SIRI:1", visibleLineIds: null }))
      .toBe("?station=ST1");
  });
});

describe("decodePermalink", () => {
  it("décode l'absence de paramètres en état par défaut", () => {
    expect(decodePermalink("")).toEqual({ stationId: null, journeyRef: null, visibleLineIds: null });
  });

  it("fait l'aller-retour sur une station seule", () => {
    const encoded = encodePermalink({ stationId: "ST1", journeyRef: null, visibleLineIds: null });
    expect(decodePermalink(encoded)).toEqual({ stationId: "ST1", journeyRef: null, visibleLineIds: null });
  });

  it("fait l'aller-retour sur un train seul", () => {
    const encoded = encodePermalink({ stationId: null, journeyRef: "SIRI:1", visibleLineIds: null });
    expect(decodePermalink(encoded)).toEqual({ stationId: null, journeyRef: "SIRI:1", visibleLineIds: null });
  });

  it("fait l'aller-retour sur plusieurs lignes", () => {
    const encoded = encodePermalink({ stationId: null, journeyRef: null, visibleLineIds: ["1", "4", "9"] });
    expect(decodePermalink(encoded)).toEqual({ stationId: null, journeyRef: null, visibleLineIds: ["1", "4", "9"] });
  });

  it("station et train tous deux présents : la station gagne, le train est abandonné", () => {
    expect(decodePermalink("?station=ST1&train=SIRI:1"))
      .toEqual({ stationId: "ST1", journeyRef: null, visibleLineIds: null });
  });

  it("lines vide décode en null, comme lines absent", () => {
    expect(decodePermalink("?lines=")).toEqual({ stationId: null, journeyRef: null, visibleLineIds: null });
    expect(decodePermalink("")).toEqual({ stationId: null, journeyRef: null, visibleLineIds: null });
  });

  it("lines composé uniquement de séparateurs décode en null", () => {
    expect(decodePermalink("?lines=,,")).toEqual({ stationId: null, journeyRef: null, visibleLineIds: null });
  });
});
