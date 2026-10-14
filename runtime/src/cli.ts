#!/usr/bin/env node
import Anthropic from "@anthropic-ai/sdk";
import { parseArgs } from "node:util";
import { JOBS } from "./jobs.js";
import { findTaf, listTafs, setup, startTaf, watch } from "./runtime.js";

const USAGE = `tafs runtime

  tafs setup                         create or update the environment and the job agents
  tafs jobs                          list the jobs
  tafs start --name N --job J        start a taf; its KIFF agent key comes from KIFF_AGENT_KEY
             [--cron "*/30 * * * *"] [--timezone Europe/Paris] [--budget-cents 200]
  tafs list                          list tafs and when they run next
  tafs run --name N                  run a taf now and follow it
  tafs pause --name N | resume --name N
  tafs watch --session S             follow a run

ANTHROPIC_API_KEY is read from the environment.`;

async function main(argv: string[]): Promise<void> {
  const [command, ...rest] = argv;
  const { values } = parseArgs({
    args: rest,
    options: {
      name: { type: "string" },
      job: { type: "string" },
      cron: { type: "string" },
      timezone: { type: "string" },
      "budget-cents": { type: "string" },
      session: { type: "string" },
    },
  });
  const need = (key: keyof typeof values): string => {
    const v = values[key];
    if (typeof v !== "string" || !v) throw new Error(`--${key} is required\n\n${USAGE}`);
    return v;
  };
  const client = () => new Anthropic();
  const print = (line: string) => console.log(line);

  switch (command) {
    case "jobs":
      for (const j of JOBS) print(`${j.id.padEnd(12)} ${j.summary}  (${j.cron})`);
      return;
    case "setup": {
      const s = await setup(client(), print);
      print(`environment ${s.environmentID}`);
      for (const [job, id] of Object.entries(s.agents)) print(`${job.padEnd(12)} ${id}`);
      return;
    }
    case "start": {
      const kiffKey = process.env.KIFF_AGENT_KEY ?? "";
      if (!kiffKey) throw new Error("set KIFF_AGENT_KEY to the taf's KIFF agent key (KIFF → Agents → new agent)");
      const c = client();
      const s = await setup(c, print);
      const budget = values["budget-cents"];
      const { deployment, sessionID } = await startTaf(c, s, {
        name: need("name"),
        job: need("job"),
        kiffKey,
        cron: values.cron,
        timezone: values.timezone,
        budgetCents: budget ? Number(budget) : undefined,
      });
      print(`started ${deployment.name} (${deployment.id}); next runs ${deployment.schedule?.upcoming_runs_at?.slice(0, 3).join(", ")}`);
      print(`first run ${sessionID}`);
      await watch(c, sessionID, print);
      return;
    }
    case "list":
      for (const d of await listTafs(client())) {
        print(`${d.name.padEnd(20)} ${d.metadata?.tafs_job?.padEnd(12)} ${d.status.padEnd(7)} next ${d.schedule?.upcoming_runs_at?.[0] ?? "-"}`);
      }
      return;
    case "run": {
      const c = client();
      const run = await c.beta.deployments.run((await findTaf(c, need("name"))).id);
      if (!run.session_id) throw new Error(`the run did not start: ${run.error?.type} ${run.error?.message}`);
      print(`run ${run.session_id}`);
      await watch(c, run.session_id, print);
      return;
    }
    case "pause":
    case "resume": {
      const c = client();
      const taf = await findTaf(c, need("name"));
      const d = command === "pause" ? await c.beta.deployments.pause(taf.id) : await c.beta.deployments.unpause(taf.id);
      print(`${d.name} is ${d.status}`);
      return;
    }
    case "watch":
      await watch(client(), need("session"), print);
      return;
    default:
      print(USAGE);
      if (command && command !== "help") process.exitCode = 2;
  }
}

main(process.argv.slice(2)).catch((err: unknown) => {
  console.error(err instanceof Error ? err.message : err);
  process.exitCode = 1;
});
