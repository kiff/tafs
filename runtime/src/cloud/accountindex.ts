/**
 * The accounts that have tafs, for the scheduled sweeps: an index, so a tick
 * reads 17 small items instead of scanning the whole table.
 *
 * At 10,000 users: 10,000 ids over 16 items (`accounts#00` … `accounts#15`, about
 * 20 KB each, far under DynamoDB's 400 KB), 17 reads at once. Without the
 * index every sweep scans the table, which grows with every account's tafs,
 * names, drafts and alerts.
 *
 * An account goes in when its tafs item is made (`tafs.put`), and out when the
 * account is erased. Once an hour, `accounts#checked` being older than that (and
 * claimed first, so of the tick's groups starting together only one scans),
 * one scan adds what the index missed (accounts made before it existed, or by
 * an older function during a deploy), so a missed account waits an hour at most,
 * and takes out an id whose tafs item is gone (an erasure that raced the scan),
 * once a consistent read says so.
 */
import { createHash } from "node:crypto";
import {
  type AttributeValue,
  ConditionalCheckFailedException,
  GetItemCommand,
  PutItemCommand,
  ScanCommand,
  UpdateItemCommand,
} from "@aws-sdk/client-dynamodb";

export const ACCOUNT_SHARDS = 16;
/** How old the last check against a scan may be before the next sweep checks again. */
export const CHECK_EVERY_MS = 60 * 60 * 1000;
const CHECKED = "accounts#checked";

/** The index item an account's id is kept in. */
export function shardOf(tenant: string): string {
  const n = createHash("sha256").update(tenant).digest().readUInt8(0) % ACCOUNT_SHARDS;
  return `accounts#${String(n).padStart(2, "0")}`;
}

/** What the index needs of DynamoDB: the client's send, as `lambda.ts` has it. */
export type Send = (command: GetItemCommand | PutItemCommand | ScanCommand | UpdateItemCommand) => Promise<unknown>;

export interface AccountIndex {
  /** Every account with tafs: the index, checked against a scan once an hour. */
  list(now: number): Promise<string[]>;
  /** The account has tafs now. */
  add(tenant: string): Promise<void>;
  /** The account is erased. */
  drop(tenant: string): Promise<void>;
}

export function accountIndex(send: Send, table: string): AccountIndex {
  /** The ids each shard holds, and when the index was last checked: 17 reads, at once. */
  async function read(): Promise<{ ids: Set<string>; checkedAt?: number }> {
    const keys = [...Array.from({ length: ACCOUNT_SHARDS }, (_, i) => `accounts#${String(i).padStart(2, "0")}`), CHECKED];
    const items = await Promise.all(keys.map(async (id) => ((await send(new GetItemCommand({
      TableName: table, Key: { id: { S: id } }, ConsistentRead: true,
    }))) as { Item?: Record<string, AttributeValue> }).Item));
    const ids = new Set<string>();
    let checkedAt: number | undefined;
    for (const item of items) {
      if (item?.id?.S === CHECKED) checkedAt = Number(item.checked_at?.N);
      else for (const id of item?.ids?.SS ?? []) ids.add(id);
    }
    return { ids, checkedAt: Number.isFinite(checkedAt) ? checkedAt : undefined };
  }

  /**
   * The hour's check, claimed before its scan: `checked_at` moves to now only if it is still what this invocation read,
   * so of the tick's groups starting together one scans and the others read the index alone.
   */
  async function claim(now: number, read: number | undefined): Promise<boolean> {
    try {
      await send(new PutItemCommand({
        TableName: table, Item: { id: { S: CHECKED }, checked_at: { N: String(now) } },
        ...(read === undefined
          ? { ConditionExpression: "attribute_not_exists(id)" }
          : { ConditionExpression: "checked_at = :read", ExpressionAttributeValues: { ":read": { N: String(read) } } }),
      }));
      return true;
    } catch (err) {
      if (err instanceof ConditionalCheckFailedException) return false;
      throw err;
    }
  }

  /** The accounts with a tafs item, as a scan of the table finds them, keys only. */
  async function scan(): Promise<string[]> {
    const out: string[] = [];
    let start: Record<string, AttributeValue> | undefined;
    do {
      const page = (await send(new ScanCommand({
        TableName: table, ProjectionExpression: "id", FilterExpression: "begins_with(id, :p)",
        ExpressionAttributeValues: { ":p": { S: "tafs#" } }, ExclusiveStartKey: start,
      }))) as { Items?: Record<string, AttributeValue>[]; LastEvaluatedKey?: Record<string, AttributeValue> };
      for (const item of page.Items ?? []) if (item.id?.S) out.push(item.id.S.slice("tafs#".length));
      start = page.LastEvaluatedKey;
    } while (start);
    return out;
  }

  /** Each shard's ids changed in one write: ADD or DELETE on its set. */
  async function change(op: "ADD" | "DELETE", tenants: string[]): Promise<void> {
    const byShard = new Map<string, string[]>();
    for (const t of tenants) byShard.set(shardOf(t), [...(byShard.get(shardOf(t)) ?? []), t]);
    for (const [shard, ids] of byShard) {
      await send(new UpdateItemCommand({
        TableName: table, Key: { id: { S: shard } }, UpdateExpression: `${op} ids :ids`,
        ExpressionAttributeValues: { ":ids": { SS: ids } },
      }));
    }
  }

  return {
    async list(now) {
      const { ids, checkedAt } = await read();
      if ((checkedAt === undefined || now - checkedAt >= CHECK_EVERY_MS) && await claim(now, checkedAt)) {
        const scanned = new Set(await scan());
        const missed = [...scanned].filter((t) => !ids.has(t));
        if (missed.length > 0) await change("ADD", missed);
        for (const t of missed) ids.add(t);
        // A scan isn't consistent: an id it didn't see goes only once its item is read as gone.
        const gone: string[] = [];
        for (const t of ids) {
          if (scanned.has(t)) continue;
          const out = (await send(new GetItemCommand({
            TableName: table, Key: { id: { S: `tafs#${t}` } }, ConsistentRead: true, ProjectionExpression: "id",
          }))) as { Item?: Record<string, AttributeValue> };
          if (!out.Item) gone.push(t);
        }
        if (gone.length > 0) await change("DELETE", gone);
        for (const t of gone) ids.delete(t);
      }
      return [...ids].sort();
    },
    async add(tenant) {
      await change("ADD", [tenant]);
    },
    async drop(tenant) {
      await change("DELETE", [tenant]);
    },
  };
}
