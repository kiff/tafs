// The signs on the page a taf works in (docs/chrome.md, "While a taf works"): a glow round the window while it works, and a
// pointer that goes to what it's about to touch, with a word for what it does ("Clicking", "Typing", "Reading"). The outline
// round that element is drawn here too, and so is Show me's.
//
// All of it is drawn, never done: no real mouse moves, nothing is focused, and every part lets each pointer through
// (`pointer-events: none`), so it never takes a click, the page's or the person's. It sits in a closed shadow root on a plain
// element with no name, class or attribute of ours, made from the extension's own world: the page can't read its words or change
// what they say. Like anything on a page, it can hide it, which is why the side panel, the tab group and the toolbar, not
// these signs, are what show a taf is at work. A read and the picture of an act look through it (page.js).
//
// A page can draw a look-alike of any of this, so none of it is what proves a taf works: that's the side panel, the tab group
// and the toolbar, which a page can't draw. Its words say only who works here, never that the page is safe.

((root) => {
  if (root.TafsSigns) return;

  let host = null;
  let parts = null;
  let watch = null;
  let alive = null;
  // What's on: the glow (while the run works), the ring (where an act or Show me points), the pointer (where it's about to act).
  const on = { glow: false, ring: false, pointer: false };

  const still = () => {
    try {
      return !!root.matchMedia && root.matchMedia("(prefers-reduced-motion: reduce)").matches;
    } catch {
      return false;
    }
  };

  function make() {
    host = document.createElement("div");
    const shadow = host.attachShadow({ mode: "closed" });
    const style = document.createElement("style");
    style.textContent =
      ":host{all:initial;position:fixed;inset:0;pointer-events:none;z-index:2147483646;display:block}" +
      "*{pointer-events:none;box-sizing:border-box}" +
      ".glow{position:fixed;inset:0;border:2px solid rgba(242,154,46,.85);box-shadow:inset 0 0 28px 6px rgba(242,154,46,.45);" +
      "border-radius:0;animation:g 2.4s ease-in-out infinite}" +
      "@keyframes g{50%{box-shadow:inset 0 0 18px 3px rgba(242,154,46,.25);border-color:rgba(242,154,46,.55)}}" +
      ".ring{position:fixed;outline:3px solid #F29A2E;border-radius:6px;box-shadow:0 0 0 4px rgba(242,154,46,.25)}" +
      ".pointer{position:fixed;left:0;top:0;transition:transform .35s cubic-bezier(.2,.7,.2,1)}" +
      ".pointer svg{display:block;width:22px;height:22px;filter:drop-shadow(0 1px 2px rgba(0,0,0,.45))}" +
      ".label{position:absolute;left:18px;top:18px;white-space:nowrap;padding:3px 8px;border-radius:999px;background:#1b1b1f;" +
      "color:#fff;font:600 12px/1.2 system-ui,sans-serif;border:1.5px solid #F29A2E}" +
      "[hidden]{display:none!important}" +
      ".still .glow{animation:none}.still .pointer{transition:none}" +
      "@media (prefers-reduced-motion:reduce){.glow{animation:none}.pointer{transition:none}}";
    const layer = document.createElement("div");
    const glow = document.createElement("div");
    glow.className = "glow";
    const ring = document.createElement("div");
    ring.className = "ring";
    const pointer = document.createElement("div");
    pointer.className = "pointer";
    const svgNS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(svgNS, "svg");
    svg.setAttribute("viewBox", "0 0 22 22");
    const arrow = document.createElementNS(svgNS, "path");
    arrow.setAttribute("d", "M2 2 L2 18 L7 13.5 L10.5 20.5 L13.5 19 L10 12 L17 12 Z");
    arrow.setAttribute("fill", "#F29A2E");
    arrow.setAttribute("stroke", "#1b1b1f");
    arrow.setAttribute("stroke-width", "1.5");
    arrow.setAttribute("stroke-linejoin", "round");
    svg.append(arrow);
    const label = document.createElement("span");
    label.className = "label";
    pointer.append(svg, label);
    layer.append(glow, ring, pointer);
    shadow.append(style, layer);
    parts = { layer, glow, ring, pointer, label };
  }

  /** Puts the layer on the page while anything of it shows, and takes it off when nothing does. */
  function place() {
    const any = on.glow || on.ring || on.pointer;
    if (!any) {
      if (watch) watch.disconnect();
      watch = null;
      clearInterval(alive);
      alive = null;
      if (host) host.remove();
      return;
    }
    if (!host) make();
    parts.layer.classList.toggle("still", still());
    parts.glow.hidden = !on.glow;
    parts.ring.hidden = !on.ring;
    parts.pointer.hidden = !on.pointer;
    if (!host.isConnected) document.documentElement.appendChild(host);
    // The page can take the element away: while a sign should show, it's put back.
    if (!watch) {
      watch = new MutationObserver(() => {
        if (host && (on.glow || on.ring || on.pointer) && !host.isConnected) document.documentElement.appendChild(host);
      });
      watch.observe(document.documentElement, { childList: true });
    }
    // An extension that was reloaded or removed can't take its signs away: this world sees it gone and takes them itself.
    if (!alive) {
      alive = setInterval(() => {
        let gone = true;
        try {
          gone = !(root.chrome && root.chrome.runtime && root.chrome.runtime.id);
        } catch {
          gone = true;
        }
        if (gone) off();
      }, 2000);
    }
  }

  /** The glow, while the run works in this tab. */
  function working() {
    on.glow = true;
    place();
    return true;
  }

  /** Every sign goes: the run paused, stopped, ended, or the tab is the person's again. */
  function off() {
    on.glow = on.ring = on.pointer = false;
    place();
    return true;
  }

  const finite = (r) => r && [r.left, r.top, r.width, r.height].every((n) => typeof n === "number" && Number.isFinite(n));

  /** The ring round [rect] (a DOMRect of the element an act or Show me names). */
  function ring(rect) {
    if (!finite(rect)) return false;
    if (!host) make();
    const s = parts.ring.style;
    s.left = rect.left - 4 + "px";
    s.top = rect.top - 4 + "px";
    s.width = rect.width + 8 + "px";
    s.height = rect.height + 8 + "px";
    on.ring = true;
    place();
    return true;
  }

  function unring() {
    on.ring = false;
    place();
    return true;
  }

  /**
   * The pointer, to the middle of [rect], with [words] beside it. With motion it glides there from where it was; with
   * `prefers-reduced-motion` it's simply there. No rect: it stays where it is (in the middle of the window the first time).
   */
  function point(rect, words) {
    if (!host) make();
    let x;
    let y;
    if (finite(rect)) {
      x = rect.left + rect.width / 2;
      y = rect.top + rect.height / 2;
    } else if (on.pointer && parts.at) {
      ({ x, y } = parts.at);
    } else {
      x = innerWidth / 2;
      y = innerHeight / 2;
    }
    parts.at = { x, y };
    parts.label.textContent = typeof words === "string" ? words.slice(0, 40) : "";
    parts.label.hidden = !parts.label.textContent;
    const was = on.pointer;
    on.pointer = true;
    // The first time it shows, it starts where it's going: nothing glides in from a corner.
    if (!was) parts.pointer.style.transition = "none";
    parts.pointer.style.transform = `translate(${Math.round(x)}px,${Math.round(y)}px)`;
    place();
    if (!was) {
      void parts.pointer.offsetWidth;
      parts.pointer.style.transition = "";
    }
    return true;
  }

  /** Where the pointer is now, in the window's coordinates, or null: for the act's own check that it points at what it touches. */
  function pointerAt() {
    return on.pointer && parts && parts.at ? { ...parts.at } : null;
  }

  /** While a picture of the page is taken: the signs aren't in it. */
  function forShot(hidden) {
    // Inside the closed root, so the page sees no change on an element of its own.
    if (parts) parts.layer.style.visibility = hidden ? "hidden" : "";
    return true;
  }

  /** Whether [node] is this layer's own element: by identity, never by a name. */
  function isOurs(node) {
    return !!host && node === host;
  }

  /** What shows now, for the tests and the runner: never read by the page. */
  function state() {
    return { glow: on.glow, ring: on.ring, pointer: on.pointer, attached: !!host && host.isConnected, words: on.pointer && parts ? parts.label.textContent : "" };
  }

  root.TafsSigns = { working, off, ring, unring, point, pointerAt, forShot, isOurs, state };
})(globalThis);
