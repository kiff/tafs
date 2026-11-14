import type { SessionEvent } from "./agents.js";

/**
 * Whether a check's list (`items`, a dotted path) is where the reads Coach made in its conversation found one, from
 * the conversation's own events: each planque read Coach made and what it answered. Coach is told never to hand over
 * a check it hasn't seen read, but copied a skill's example path (`data.messages`) that the app's answer doesn't have,
 * and every Gmail taf's first check failed (14 Nov). So the hand-over says it, with the paths the reads do have.
 *
 * Undefined when the check fits, when no read in the conversation answered JSON (nothing to judge by), or when any read
 * answered text that isn't whole JSON: kiff/agents cuts a long answer (32 KB), and a cut list read may be the very one
 * that has the list, so nothing is judged by the reads left (kiff-qa on this guard). The first check still tells then.
 * Otherwise the words for Coach.
 */
export function checkMisfitSeen(events: SessionEvent[], check: unknown): string | undefined {
  const items = typeof (check as { items?: unknown } | null)?.items === "string" ? (check as { items: string }).items.trim() : "";
  if (!items) return undefined;
  const reads = new Set<string>();
  const answers: unknown[] = [];
  let unread = false;
  for (const e of events) {
    const b = (e.body ?? {}) as { tool?: unknown; input?: unknown; call_id?: unknown; text?: unknown; is_error?: unknown };
    if (e.kind === "tool_call" && b.tool === "planque" && (b.input as { action?: unknown } | null)?.action === "read" && typeof b.call_id === "string") {
      reads.add(b.call_id);
    }
    if (e.kind === "tool_result" && typeof b.call_id === "string" && reads.has(b.call_id) && b.is_error !== true && typeof b.text === "string") {
      const parsed = jsonOf(b.text);
      if (parsed !== undefined) answers.push(parsed);
      else unread = true;
    }
  }
  if (answers.length === 0 || unread) return undefined;
  if (answers.some((a) => Array.isArray(at(a, items)))) return undefined;
  const lists = [...new Set(answers.flatMap((a) => listPaths(a, "", 0)))].slice(0, 8);
  return lists.length
    ? `the check's items, ${items}, isn't a list in what the reads you made answered: their lists are at ${lists.join(", ")}. Take items (and its cursor and id, inside each item) from a read you've seen, and hand over again`
    : `the check's items, ${items}, isn't a list in what the reads you made answered, and they have no list: read the app with the check's own tool and arguments, take items from what it answers, and hand over again`;
}

/** The JSON a read answered: the whole text, or the first {…} or […] in it. */
function jsonOf(text: string): unknown {
  try { return JSON.parse(text); } catch { /* the text may carry words around it */ }
  const start = text.search(/[[{]/);
  if (start < 0) return undefined;
  try { return JSON.parse(text.slice(start)); } catch { return undefined; }
}

/** The value at a dotted path. */
function at(v: unknown, path: string): unknown {
  let cur: unknown = v;
  for (const k of path.split(".")) {
    if (!cur || typeof cur !== "object" || Array.isArray(cur)) return undefined;
    cur = (cur as Record<string, unknown>)[k];
  }
  return cur;
}

/** The dotted paths of the lists in a value, not inside a list, at most 4 deep. */
function listPaths(v: unknown, prefix: string, depth: number): string[] {
  if (!v || typeof v !== "object" || Array.isArray(v) || depth > 4) return [];
  const out: string[] = [];
  for (const [k, val] of Object.entries(v as Record<string, unknown>).slice(0, 50)) {
    const p = prefix ? `${prefix}.${k}` : k;
    if (Array.isArray(val)) out.push(p);
    else out.push(...listPaths(val, p, depth + 1));
  }
  return out;
}
