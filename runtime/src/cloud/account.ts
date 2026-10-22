/**
 * Which of tafs cloud's table items are an account's, for deleting the
 * account (kiff/tafs#185). Every item's id names its account: some are one
 * per account (its tafs, phones, removals, alert secret's hash), others one
 * per taf, draft, name or flood window under the account's prefix.
 * docs/data.md lists them all.
 */

/** The ids of the items there is one of per account. */
export function accountItems(tenant: string): string[] {
  return [`tafs#${tenant}`, `push#${tenant}`, `removing#${tenant}`, `account#${tenant}`];
}

/** The prefixes of the account's other items: archives, claimed drafts, flood slots, delivered alerts and taf names. */
export function accountPrefixes(tenant: string): string[] {
  return [`archived#${tenant}#`, `draft#${tenant}#`, `rate#${tenant}#`, `alert#${tenant}#`, `sending#${tenant}#`, `${tenant}#`];
}

/** Whether the item with this id is the account's. */
export function isAccountItem(id: string, tenant: string): boolean {
  if (!tenant || tenant.includes("#")) return false;
  return accountItems(tenant).includes(id) || accountPrefixes(tenant).some((p) => id.startsWith(p));
}
