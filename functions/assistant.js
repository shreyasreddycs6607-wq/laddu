/**
 * Pure logic for the cloud pet assistant (no Firebase imports, so `node --test` can run it).
 * The model only writes prose around facts the SERVER computed from authorised events; every number the
 * app shows comes from `facts`, never from model text. Model output is untrusted and validated here.
 */
const MAX_EVENTS = 200;
const MAX_QUESTION = 400;
const MAX_ANSWER = 1500;
const MAX_REFS = 10;
const DAY_MS = 86400000;

function cleanQuestion(q) {
  if (typeof q !== "string") return null;
  const t = q.replace(/\s+/g, " ").trim();
  return t && t.length <= MAX_QUESTION ? t : null;
}

/** events: [{id,type,timestamp,metadata}] already filtered to cameras the caller may read. */
function buildFacts(events, fromMs, toMs) {
  const inRange = events.filter((e) => e.timestamp >= fromMs && e.timestamp <= toMs);
  const used = inRange.slice(-MAX_EVENTS);
  const byType = {};
  for (const e of used) byType[e.type] = (byType[e.type] || 0) + 1;
  const hazards = used
    .filter((e) => /HAZARD|CHEWING|INGESTION|UNKNOWN_OBJECT/.test(e.type))
    .map((e) => ({ id: e.id, type: e.type, timestamp: e.timestamp, risk: (e.metadata && e.metadata.risk) || null }));
  return {
    fromMs, toMs,
    eventCount: used.length,
    truncated: inRange.length > used.length, // older events were left out: the answer must say so
    byType, hazards,
    eventIds: used.map((e) => e.id),
  };
}

function rangeFor(daysBack, nowMs) {
  const d = Math.min(Math.max(Math.floor(Number(daysBack) || 1), 1), 7);
  return [nowMs - d * DAY_MS, nowMs];
}

/** Throws on anything the app should not trust. Returns the cleaned answer. */
function validateAnswer(out, allowedIds) {
  if (!out || typeof out !== "object") throw new Error("not an object");
  if (typeof out.answer !== "string" || !out.answer.trim() || out.answer.length > MAX_ANSWER) throw new Error("bad answer");
  const refs = Array.isArray(out.references) ? out.references : [];
  if (refs.length > MAX_REFS) throw new Error("too many references");
  const allowed = new Set(allowedIds);
  for (const r of refs) if (typeof r !== "string" || !allowed.has(r)) throw new Error("reference to an unknown event");
  return { answer: out.answer.trim(), references: [...new Set(refs)] };
}

/** Offline stand-in used for tests and emulator runs (ASSISTANT_URL=mock). */
function mockProvider(req) {
  const f = req.facts;
  return {
    answer: `Mock reply: ${f.eventCount} events recorded in this period.` + (f.truncated ? " Older events were not included." : ""),
    references: f.hazards.slice(0, 3).map((h) => h.id),
  };
}

module.exports = { cleanQuestion, buildFacts, rangeFor, validateAnswer, mockProvider, MAX_EVENTS };
