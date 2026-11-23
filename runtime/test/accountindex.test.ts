import { type AttributeValue, ConditionalCheckFailedException } from "@aws-sdk/client-dynamodb";
import { describe, expect, it } from "vitest";
import { ACCOUNT_SHARDS, CHECK_EVERY_MS, accountIndex, shardOf } from "../src/cloud/accountindex.js";

type Item = Record<string, AttributeValue>;

/** A table as DynamoDB keeps it, for the commands the index sends; a scan pages two items at a time. */
function table(opts: { scanMisses?: string[] } = {}) {
  const items = new Map<string, Item>();
  const sent: string[] = [];
  const send = async (c: { constructor: { name: string }; input: unknown }): Promise<unknown> => {
    const name = c.constructor.name;
    sent.push(name);
    const input = c.input as Record<string, any>;
    switch (name) {
      case "GetItemCommand":
        return { Item: items.get(input.Key.id.S) };
      case "PutItemCommand": {
        const had = items.get(input.Item.id.S);
        const cond = input.ConditionExpression as string | undefined;
        if ((cond === "attribute_not_exists(id)" && had) ||
          (cond === "checked_at = :read" && had?.checked_at?.N !== input.ExpressionAttributeValues[":read"].N)) {
          throw new ConditionalCheckFailedException({ message: "the condition", $metadata: {} });
        }
        items.set(input.Item.id.S, input.Item);
        return {};
      }
      case "UpdateItemCommand": {
        const id = input.Key.id.S as string;
        const [op] = (input.UpdateExpression as string).split(" ");
        const now = new Set(items.get(id)?.ids?.SS ?? []);
        for (const v of input.ExpressionAttributeValues[":ids"].SS as string[]) op === "ADD" ? now.add(v) : now.delete(v);
        if (now.size === 0) items.delete(id); else items.set(id, { id: { S: id }, ids: { SS: [...now] } });
        return {};
      }
      case "ScanCommand": {
        const all = [...items.keys()].filter((k) => k.startsWith("tafs#") && !(opts.scanMisses ?? []).includes(k)).sort();
        const from = input.ExclusiveStartKey ? all.indexOf(input.ExclusiveStartKey.id.S) + 1 : 0;
        const page = all.slice(from, from + 2);
        return { Items: page.map((id) => ({ id: { S: id } })), LastEvaluatedKey: from + 2 < all.length ? { id: { S: page[page.length - 1] } } : undefined };
      }
    }
    throw new Error(`unexpected ${name}`);
  };
  return { items, sent, send: send as never };
}

const hour = CHECK_EVERY_MS;

describe("the sweeps' account index", () => {
  it("puts each account in one of 16 shards, the same one each time", () => {
    const shards = new Set(Array.from({ length: 400 }, (_, i) => shardOf(`t_${i}`)));
    expect(shards.size).toBe(ACCOUNT_SHARDS);
    expect(shardOf("t_acme")).toBe(shardOf("t_acme"));
    expect([...shards].every((s) => /^accounts#\d\d$/.test(s))).toBe(true);
  });

  it("finds the accounts made before it existed with one scan, then reads only the index within the hour", async () => {
    const t = table();
    for (const id of ["t_a", "t_b", "t_c"]) t.items.set(`tafs#${id}`, { id: { S: `tafs#${id}` } });
    t.items.set("push#t_a", { id: { S: "push#t_a" } });
    const ix = accountIndex(t.send, "names");
    expect(await ix.list(0)).toEqual(["t_a", "t_b", "t_c"]);
    expect(t.sent).toContain("ScanCommand");

    t.sent.length = 0;
    expect(await ix.list(hour - 1)).toEqual(["t_a", "t_b", "t_c"]);
    expect(t.sent).toEqual(Array(17).fill("GetItemCommand"));
  });

  it("lists an account added since without a scan", async () => {
    const t = table();
    const ix = accountIndex(t.send, "names");
    await ix.list(0);
    t.items.set("tafs#t_new", { id: { S: "tafs#t_new" } });
    await ix.add("t_new");
    t.sent.length = 0;
    expect(await ix.list(10)).toEqual(["t_new"]);
    expect(t.sent).not.toContain("ScanCommand");
  });

  it("adds an account it missed at the next hour's check, and only then", async () => {
    const t = table();
    const ix = accountIndex(t.send, "names");
    await ix.list(0);
    t.items.set("tafs#t_old", { id: { S: "tafs#t_old" } }); // made by an older function: never added
    expect(await ix.list(hour - 1)).toEqual([]);
    expect(await ix.list(hour)).toEqual(["t_old"]);
    expect(t.items.get(shardOf("t_old"))?.ids?.SS).toContain("t_old");
  });

  it("drops an erased account at once", async () => {
    const t = table();
    t.items.set("tafs#t_a", { id: { S: "tafs#t_a" } });
    const ix = accountIndex(t.send, "names");
    await ix.list(0);
    t.items.delete("tafs#t_a");
    await ix.drop("t_a");
    expect(await ix.list(1)).toEqual([]);
    expect(t.items.has(shardOf("t_a"))).toBe(false);
  });

  it("takes out an id whose tafs item is gone at the hour's check, but not one the scan only missed", async () => {
    const t = table({ scanMisses: ["tafs#t_live"] });
    const ix = accountIndex(t.send, "names");
    await ix.list(0);
    t.items.set("tafs#t_live", { id: { S: "tafs#t_live" } });
    await ix.add("t_live");
    await ix.add("t_gone"); // its erasure raced a scan: in the index, no tafs item
    expect(await ix.list(hour)).toEqual(["t_live"]);
    expect(t.items.get(shardOf("t_gone"))?.ids?.SS ?? []).not.toContain("t_gone");
  });

  it("scans once an hour however many of the tick's groups read it at the same moment", async () => {
    const t = table();
    for (const id of ["t_a", "t_b"]) t.items.set(`tafs#${id}`, { id: { S: `tafs#${id}` } });
    const groups = Array.from({ length: 5 }, () => accountIndex(t.send, "names"));
    const lists = await Promise.all(groups.map((g) => g.list(0)));
    expect(t.sent.filter((s) => s === "ScanCommand")).toHaveLength(1);
    // The one that scanned has both; the others read the index as it was, and the next tick has both everywhere.
    expect(lists.some((l) => l.length === 2)).toBe(true);
    t.sent.length = 0;
    const again = await Promise.all(groups.map((g) => g.list(hour - 1)));
    expect(again.every((l) => l.length === 2)).toBe(true);
    expect(t.sent).not.toContain("ScanCommand");
    // An hour on, again one scan.
    await Promise.all(groups.map((g) => g.list(hour * 2)));
    expect(t.sent.filter((s) => s === "ScanCommand")).toHaveLength(1);
  });
});
