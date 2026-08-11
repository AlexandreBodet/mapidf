// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { StaleWarning } from "./StaleWarning";

afterEach(cleanup);

describe("StaleWarning", () => {
  it("reste muette quand les positions se rafraîchissent", () => {
    render(<StaleWarning stale={false} />);

    expect(screen.queryByRole("status")).toBeNull();
  });

  it("annonce le gel par un role=status, pour qu'une panne ne soit jamais silencieuse", () => {
    render(<StaleWarning stale />);

    expect(screen.getByRole("status").textContent).toContain("Positions plus mises à jour");
  });
});
