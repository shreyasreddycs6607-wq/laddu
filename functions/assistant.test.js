const test = require("node:test");
const assert = require("node:assert");
const a = require("./assistant");

const ev = (id, type, ts, risk) => ({ id, type, timestamp: ts, metadata: risk ? { risk } : {} });
const now = 10 * 86400000;

test("facts count only events in range and flag truncation", () => {
  const evs = [ev("old", "BARK", 1), ev("b1", "BARK", now - 1000), ev("h1", "POSSIBLE_CHEWING", now - 500, "HIGH")];
  const f = a.buildFacts(evs, now - 86400000, now);
  assert.equal(f.eventCount, 2);
  assert.deepEqual(f.byType, { BARK: 1, POSSIBLE_CHEWING: 1 });
  assert.equal(f.hazards[0].risk, "HIGH");
  assert.equal(f.truncated, false);
  const many = Array.from({ length: 250 }, (_, i) => ev("e" + i, "BARK", now - 1000 + i));
  const g = a.buildFacts(many, now - 86400000, now);
  assert.equal(g.eventCount, a.MAX_EVENTS);
  assert.equal(g.truncated, true);
});

test("a reference to an event the caller cannot see is rejected", () => {
  assert.throws(() => a.validateAnswer({ answer: "x", references: ["secret"] }, ["h1"]));
  assert.deepEqual(a.validateAnswer({ answer: " ok ", references: ["h1", "h1"] }, ["h1"]), { answer: "ok", references: ["h1"] });
});

test("malformed or oversized model output is rejected", () => {
  for (const bad of [null, {}, { answer: "" }, { answer: 5 }, { answer: "x".repeat(1501) }])
    assert.throws(() => a.validateAnswer(bad, []));
});

test("question and day-range limits", () => {
  assert.equal(a.cleanQuestion("  what   happened? "), "what happened?");
  assert.equal(a.cleanQuestion("x".repeat(401)), null);
  assert.equal(a.cleanQuestion(7), null);
  assert.equal(a.rangeFor(99, now)[0], now - 7 * 86400000);
  assert.equal(a.rangeFor("nope", now)[0], now - 86400000);
});

test("mock provider only cites events from the facts it was given", () => {
  const f = a.buildFacts([ev("h1", "POSSIBLE_INGESTION", now - 5, "CRITICAL")], now - 86400000, now);
  const out = a.validateAnswer(a.mockProvider({ facts: f }), f.eventIds);
  assert.deepEqual(out.references, ["h1"]);
});
