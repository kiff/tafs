import { describe, expect, it } from "vitest";
import { isAccountItem } from "../src/cloud/account.js";

describe("an account's items (kiff/tafs#185)", () => {
  it("are every item whose id names the account, and none of another's", () => {
    for (const id of ["tafs#t_acme", "push#t_acme", "removing#t_acme", "account#t_acme", "archived#t_acme#taf-1", "draft#t_acme#drf_1",
      "rate#t_acme#taf-1#123#0", "alert#t_acme#evt#tok", "sending#t_acme#evt#tok", "t_acme#Rae"]) {
      expect(isAccountItem(id, "t_acme"), id).toBe(true);
    }
    for (const id of ["tafs#t_acme2", "t_acme2#Rae", "archived#t_acme2#taf-1", "push#t_other", "forward#evt#0", "tafs#t_ac"]) {
      expect(isAccountItem(id, "t_acme"), id).toBe(false);
    }
  });

  it("can't be asked for an account id that would match others", () => {
    expect(isAccountItem("tafs#", "")).toBe(false);
    expect(isAccountItem("x#y#Rae", "x#y")).toBe(false);
  });
});
