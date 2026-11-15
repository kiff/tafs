/**
 * Which of the person's tafs need an app, so a connection made from Connect apps goes only to a taf that uses it
 * (docs/connect-apps.md, "Which taf"): the owner's 105 retest found Stripe connected under "Unanswered emails", a
 * Gmail taf, because both apps took the only taf there was. tafs cloud decides it here, once, for every app:
 * Connect apps lists `needed_by` per app, and starting a connection refuses a taf not in it (`not_needed`).
 *
 * A taf needs an app when its recipe names the app's vendor in one of its connections, or when a Card in force for
 * it has a rule on an action of one of the app's connections (planque's grants for the app, whichever taf asked
 * for them: KIFF's tools are the account's). The second needs planque's grants, which only the phone's signed read
 * gives. Without them, a taf that already holds one of the app's connections counts too (its reconnect from the
 * list), since only the grants and Cards can say it was made under the wrong taf: with them, the Card decides, and a
 * connection held by a taf whose Card doesn't use it is `misplaced`.
 */
import { vendorOf } from "./recipes.js";
import type { GrantView } from "./taf-setup.js";
import type { Mandate } from "./home.js";

/** A taf as this decision reads it: its id, its name for people, its KIFF agent, and the recipe it was built from. */
export interface NeedTaf { id: string; name: string; kiffAgent: string; recipe?: string }

/** A recipe as this decision reads it: its id, and each connection it needs as the vendors that would do. */
export interface NeedRecipe { id: string; connections: readonly (readonly string[])[] }

/** An app as Connect apps lists it, or as a POST names it (its entry alone). */
export interface NeedApp { entry: string; vendor?: string; title?: string }

/** A taf that needs the app, as the apps draw it in "Which taf?". */
export interface NeededBy { taf: string; name: string }

/** A live connection of the app made under a taf that doesn't need it, for the apps to offer to withdraw. */
export interface Misplaced { grant: string; taf: string; name: string }

function fold(name: string): string {
  return name.toLowerCase().replace(/^_+/, "").replace(/[\s._-]+/g, "");
}

/** The app's vendor names, folded as recipes name them: its entry's slug, its vendor and its title. */
export function appVendors(app: NeedApp): Set<string> {
  const names = vendorOf({ entry: app.entry, vendor: app.vendor ?? "" });
  if (app.title) names.push(fold(app.title));
  return new Set(names.filter(Boolean));
}

/** planque's grants for the app: by its catalog entry, else by its vendor's name. */
export function grantsOf(app: NeedApp, grants: readonly GrantView[]): GrantView[] {
  const vendors = appVendors(app);
  return grants.filter((g) => (g.entry && g.entry === app.entry) || (g.vendor && vendors.has(fold(g.vendor))));
}

/** A grant's actions as KIFF names them: planque's connected tools, else each asked tool's name, never Setup's reads. */
function actionsOf(g: GrantView): Set<string> {
  const listed = Array.isArray(g.tools) ? g.tools : (g.request?.tools ?? []).filter((t) => t.setup_reads !== true).map((t) => t.name || t.tool);
  return new Set(listed.filter((a): a is string => typeof a === "string" && a.length > 0));
}

const live = (g: GrantView) => g.status !== "disconnected" && g.status !== "failed";

/**
 * The tafs that need the app, in name order. `grants` and `cards` are planque's grants (the phone's signed read) and
 * the person's Cards; leave them out when they can't be read. `holding` is the tafs that hold one of the app's
 * connections, as the account's connections say: counted only without the grants and Cards.
 */
export function neededBy(app: NeedApp, tafs: readonly NeedTaf[], recipes: readonly NeedRecipe[], grants?: readonly GrantView[], cards?: readonly Mandate[],
  holding: ReadonlySet<string> = new Set()): NeededBy[] {
  const vendors = appVendors(app);
  const byRecipe = (t: NeedTaf) => {
    const r = t.recipe ? recipes.find((x) => x.id === t.recipe) : undefined;
    return !!r && r.connections.some((group) => group.some((v) => vendors.has(fold(v))));
  };
  const actions = new Set(grants && cards ? grantsOf(app, grants).filter(live).flatMap((g) => [...actionsOf(g)]) : []);
  const byCard = (t: NeedTaf) => actions.size > 0 && !!cards && cards.some((m) => m.subject === t.kiffAgent && m.active && !m.revoked_at
    && (m.grants ?? []).some((x) => actions.has(x.action)));
  // Holding a connection counts only when the grants and Cards can't be read to say whether it's used.
  const byHolding = (t: NeedTaf) => !(grants && cards) && holding.has(t.id);
  return tafs.filter((t) => t.kiffAgent && (byRecipe(t) || byCard(t) || byHolding(t)))
    .map((t) => ({ taf: t.id, name: t.name })).sort((a, b) => a.name.localeCompare(b.name) || a.taf.localeCompare(b.taf));
}

/**
 * The app's live connections made under one of these tafs that doesn't need it (the owner's Stripe under "Unanswered
 * emails"): listed so the person can withdraw it, never kept silently. Needs planque's grants; none without them.
 */
export function misplaced(app: NeedApp, tafs: readonly NeedTaf[], needed: readonly NeededBy[], grants?: readonly GrantView[]): Misplaced[] {
  if (!grants) return [];
  const needs = new Set(needed.map((n) => n.taf));
  const byAgent = new Map(tafs.filter((t) => t.kiffAgent).map((t) => [t.kiffAgent, t]));
  return grantsOf(app, grants).filter(live).flatMap((g) => {
    const t = byAgent.get(g.request?.agent ?? "");
    return t && !needs.has(t.id) ? [{ grant: g.id, taf: t.id, name: t.name }] : [];
  }).sort((a, b) => a.name.localeCompare(b.name) || a.grant.localeCompare(b.grant));
}
