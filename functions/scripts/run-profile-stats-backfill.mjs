/**
 * Local admin backfill runner (uses Application Default Credentials).
 * Usage:
 *   node scripts/run-profile-stats-backfill.mjs           # dry run
 *   node scripts/run-profile-stats-backfill.mjs --apply   # write fixes
 */
import admin from "firebase-admin";
import { getFirestore } from "firebase-admin/firestore";
import {
  markProfileStatsBackfillComplete,
  runProfileMatchStatsBackfill,
} from "../lib/profileStatsBackfill.js";

const apply = process.argv.includes("--apply");

admin.initializeApp({ projectId: "rps-online-9771e" });
const db = getFirestore();

const summary = await runProfileMatchStatsBackfill(db, !apply);
console.log(JSON.stringify(summary, null, 2));

if (apply) {
  await markProfileStatsBackfillComplete(db, summary);
  console.log("Recorded completion at maintenance/profileMatchStatsBackfill");
}
