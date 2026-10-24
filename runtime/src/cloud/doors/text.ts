import { DOOR } from "./model.js";

/**
 * What a door keeps of a message: its text, without the history it quotes and
 * without a signature, cut to the door's limit. HTML is read only when a
 * message has no text part, and only for its words.
 */

/** Lines that open the quoted history of a reply, in the languages tafs' owners write. */
const QUOTE_OPENERS: RegExp[] = [
  /^On .{1,300}wrote:\s*$/i,
  /^El .{1,300}escribi[oó]:\s*$/i,
  /^Le .{1,300}a [ée]crit\s*:\s*$/i,
  /^Am .{1,300}schrieb .{0,200}:\s*$/i,
  /^Il .{1,300}ha scritto:\s*$/i,
  /^Op .{1,300}schreef .{0,200}:\s*$/i,
  /^Em .{1,300}escreveu:\s*$/i,
  /^-{2,}\s*Original Message\s*-{2,}\s*$/i,
  /^-{2,}\s*Forwarded message\s*-{2,}\s*$/i,
  /^_{10,}\s*$/,
  /^(From|De|Von|Da|Van):\s.+$/i,
];

/** The text a person wrote, without quoted history or a signature. */
export function ownText(text: string): string {
  const lines = text.replace(/\r\n?/g, "\n").split("\n");
  const kept: string[] = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i] ?? "";
    const t = line.trim();
    // A header block ("From: … / Sent: …") only counts when it's a block, not a sentence.
    if (/^(From|De|Von|Da|Van):\s/i.test(t)) {
      const next = (lines[i + 1] ?? "").trim();
      if (/^(Sent|Date|Enviado|Fecha|Envoy[ée]|Gesendet|Datum|Inviato|Verzonden|Data):\s/i.test(next)) break;
      kept.push(line);
      continue;
    }
    // "On … wrote:" can wrap onto a second line.
    const two = `${t} ${(lines[i + 1] ?? "").trim()}`;
    if (QUOTE_OPENERS.some((r) => r.test(t)) || (/^(On|El|Le|Am|Il|Op|Em)\s/i.test(t) && QUOTE_OPENERS.slice(0, 7).some((r) => r.test(two)))) break;
    if (t === "--" || line === "-- ") break;
    if (t.startsWith(">")) continue;
    kept.push(line);
  }
  return kept.join("\n").replace(/\n{3,}/g, "\n\n").trim();
}

const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: "\"", apos: "'", nbsp: " " };

/** The words of an HTML body: quoted parts, styles and scripts dropped, tags removed. */
export function htmlText(html: string): string {
  return html
    .replace(/<(style|script|head)[\s\S]*?<\/\1>/gi, "")
    .replace(/<blockquote[\s\S]*?<\/blockquote>/gi, "")
    .replace(/<div[^>]*class="?gmail_quote[\s\S]*$/i, "")
    .replace(/<(br|\/p|\/div|\/li|\/tr|\/h\d)[^>]*>/gi, "\n")
    .replace(/<[^>]+>/g, "")
    .replace(/&(#x?[0-9a-f]+|\w+);/gi, (m, e: string) => {
      if (e[0] === "#") {
        const n = e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
        return Number.isFinite(n) && n > 0 && n < 0x110000 ? String.fromCodePoint(n) : m;
      }
      return ENTITIES[e.toLowerCase()] ?? m;
    })
    .replace(/[ \t]+\n/g, "\n");
}

/** The text cut to the door's limits: characters, then bytes. */
export function cut(text: string): { text: string; cut: boolean } {
  let out = text;
  let was = false;
  if ([...out].length > DOOR.maxChars) {
    out = [...out].slice(0, DOOR.maxChars).join("");
    was = true;
  }
  while (Buffer.byteLength(out, "utf8") > DOOR.maxBytes) {
    out = [...out].slice(0, Math.floor([...out].length * 0.9)).join("");
    was = true;
  }
  return { text: out, cut: was };
}

/** An email address from "Name <a@b>" or "a@b", lowercase; "" when there is none. */
export function address(from: string): string {
  const m = from.match(/<([^<>\s]+@[^<>\s]+)>/) ?? from.match(/([^\s<>"]+@[^\s<>"]+)/);
  return (m?.[1] ?? "").toLowerCase();
}

/** The display name in "Name <a@b>", unquoted; undefined when there is none. */
export function displayName(from: string): string | undefined {
  const m = from.match(/^\s*"?([^"<]*?)"?\s*</);
  const name = m?.[1]?.trim();
  return name ? name : undefined;
}
