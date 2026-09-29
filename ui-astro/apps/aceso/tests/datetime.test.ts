/**
 * Unit tests for the shared Aceso date/time boundary (`src/lib/datetime.ts`).
 *
 * Runner: the built-in `node:test` + `node:assert/strict` — no new dependency.
 * Run from `ui-astro/` under several host zones to prove independence:
 *
 *   TZ=Asia/Shanghai     node --test apps/aceso/tests/datetime.test.ts
 *   TZ=UTC               node --test apps/aceso/tests/datetime.test.ts
 *   TZ=America/New_York  node --test apps/aceso/tests/datetime.test.ts
 *
 * Every assertion is an exact string so a host-zone leak fails loudly.
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
	BUSINESS_TIME_ZONE,
	addDays,
	dayBoundary,
	daysAgoLocal,
	formatDate,
	formatDateTime,
	formatTime,
	nowLocalInput,
	todayLocal,
	toInputValue,
	toOffsetDateTime,
	weekStartOf,
} from "../src/lib/datetime.ts";

/**
 * Independent reference implementation of the Shanghai wall clock, using the
 * `sv-SE` locale so the formatted text is already "YYYY-MM-DD HH:mm:ss".
 * Deliberately not sharing any code with `datetime.ts`.
 */
function shanghaiReference(instant: Date): string {
	return new Intl.DateTimeFormat("sv-SE", {
		timeZone: "Asia/Shanghai",
		year: "numeric",
		month: "2-digit",
		day: "2-digit",
		hour: "2-digit",
		minute: "2-digit",
		second: "2-digit",
		hourCycle: "h23",
	}).format(instant);
}

// ---------------------------------------------------------------------------
// formatDateTime
// ---------------------------------------------------------------------------

test("formatDateTime: UTC instant is rendered in the business zone", () => {
	assert.equal(formatDateTime("2026-09-28T07:17:00Z"), "2026-09-28 15:17");
	assert.equal(formatDateTime("2026-09-28T07:17:00.000Z"), "2026-09-28 15:17");
});

test("formatDateTime: an explicit +08:00 instant stays on its own wall clock", () => {
	assert.equal(formatDateTime("2026-09-28T07:17:00+08:00"), "2026-09-28 07:17");
});

test("formatDateTime: offset-less datetime-local text is Shanghai wall clock", () => {
	assert.equal(formatDateTime("2026-09-28T07:17"), "2026-09-28 07:17");
	assert.equal(formatDateTime("2026-09-28T07:17:00"), "2026-09-28 07:17");
});

test("formatDateTime: instants that roll over the business-zone date", () => {
	assert.equal(formatDateTime("2026-09-28T23:17:00Z"), "2026-09-29 07:17");
	assert.equal(formatDateTime("2026-06-30T16:00:00Z"), "2026-07-01 00:00");
});

test("formatDateTime: a date-only value renders as a date, with no invented time (F2)", () => {
	// A DATE column has no time of day; "00:00" would fabricate one. This matches
	// the pre-migration rendering exactly.
	assert.equal(formatDateTime("2026-09-28"), "2026-09-28");
	assert.equal(formatDateTime("2026-07-01"), "2026-07-01");
	assert.equal(formatDateTime("2026-07-01", "—"), "2026-07-01");
	// The custom `empty` fallback still applies only to blank input.
	assert.equal(formatDateTime("", "—"), "—");
});

test("formatDateTime: an instant landing on business midnight still renders its time", () => {
	assert.equal(formatDateTime("2026-06-30T16:00:00Z"), "2026-07-01 00:00");
});

test("formatDateTime: fallbacks for null/undefined/blank, custom empty", () => {
	assert.equal(formatDateTime(null), "-");
	assert.equal(formatDateTime(undefined), "-");
	assert.equal(formatDateTime(""), "-");
	assert.equal(formatDateTime("   "), "-");
	assert.equal(formatDateTime("", "—"), "—");
	assert.equal(formatDateTime(null, ""), "");
});

test("formatDateTime: unparseable non-blank text is passed through unchanged", () => {
	assert.equal(formatDateTime("not-a-timestamp"), "not-a-timestamp");
	assert.equal(formatDateTime("garbage input"), "garbage input");
});

test("formatDateTime/toInputValue: a fraction without seconds is not a wall clock (F6)", () => {
	// Not a valid datetime-local shape, and V8 parses it as an Invalid Date, so it
	// surfaces raw instead of being interpreted in the host zone.
	assert.equal(formatDateTime("2026-09-28T15:17.123456"), "2026-09-28T15:17.123456");
	assert.equal(toInputValue("2026-09-28T15:17.123456"), "2026-09-28T15:17.123456");
});

test("formatDateTime: a fraction after explicit seconds is accepted", () => {
	assert.equal(formatDateTime("2026-09-28T07:17:30.123456"), "2026-09-28 07:17");
	assert.equal(toInputValue("2026-09-28T07:17:30.123456"), "2026-09-28T07:17");
});

// ---------------------------------------------------------------------------
// formatDate
// ---------------------------------------------------------------------------

test("formatDate: instant -> business-zone calendar date", () => {
	assert.equal(formatDate("2026-09-28T07:17:00Z"), "2026-09-28");
	assert.equal(formatDate("2026-06-30T16:00:00Z"), "2026-07-01");
	assert.equal(formatDate("2026-09-28T23:17:00Z"), "2026-09-29");
	assert.equal(formatDate("2026-09-28T07:17:00+08:00"), "2026-09-28");
});

test("formatDate: date-only text passes through verbatim", () => {
	assert.equal(formatDate("2026-07-01"), "2026-07-01");
	assert.equal(formatDate("2026-09-28"), "2026-09-28");
});

test("formatDate: offset-less wall clock keeps its own date", () => {
	assert.equal(formatDate("2026-09-28T07:17"), "2026-09-28");
	assert.equal(formatDate("2026-09-28T00:00"), "2026-09-28");
});

test("formatDate: fallbacks and pass-through of bad input", () => {
	assert.equal(formatDate(null), "-");
	assert.equal(formatDate("", "—"), "—");
	assert.equal(formatDate("nope"), "nope");
});

// ---------------------------------------------------------------------------
// formatTime
// ---------------------------------------------------------------------------

test("formatTime: instant -> business-zone HH:mm", () => {
	assert.equal(formatTime("2026-09-28T07:17:00Z"), "15:17");
	assert.equal(formatTime("2026-09-28T07:17:00+08:00"), "07:17");
	assert.equal(formatTime("2026-09-28T07:17"), "07:17");
	assert.equal(formatTime("2026-09-28T07:17:59"), "07:17");
	assert.equal(formatTime("2026-06-30T16:00:00Z"), "00:00");
});

test("formatTime: a date-only value has no time", () => {
	assert.equal(formatTime("2026-07-01"), "-");
	assert.equal(formatTime("2026-07-01", "—"), "—");
});

test("formatTime: fallbacks and pass-through of bad input", () => {
	assert.equal(formatTime(null), "-");
	assert.equal(formatTime(undefined, ""), "");
	assert.equal(formatTime("nope"), "nope");
});

// ---------------------------------------------------------------------------
// todayLocal / nowLocalInput / daysAgoLocal
// ---------------------------------------------------------------------------

test("todayLocal: business-zone date, matching an independent reference", () => {
	assert.match(todayLocal(), /^\d{4}-\d{2}-\d{2}$/);
	const before = shanghaiReference(new Date()).slice(0, 10);
	const actual = todayLocal();
	const after = shanghaiReference(new Date()).slice(0, 10);
	// Tolerate crossing local midnight between the two reads.
	assert.ok(
		actual === before || actual === after,
		`todayLocal() = ${actual} is neither ${before} nor ${after}`,
	);
});

test("nowLocalInput: shape and agreement with an independent Shanghai reference", () => {
	const actual = nowLocalInput();
	assert.match(actual, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);

	const before = shanghaiReference(new Date()).slice(0, 16).replace(" ", "T");
	const after = shanghaiReference(new Date()).slice(0, 16).replace(" ", "T");
	// Allow the minute boundary to tick between the reads.
	assert.ok(
		actual === before || actual === after,
		`nowLocalInput() = ${actual} is neither ${before} nor ${after}`,
	);
});

test("formatDateTime/toInputValue: agree with the reference for a pinned live instant", () => {
	// One instant, captured once: no minute-boundary tolerance is needed.
	const iso = new Date().toISOString();
	const expected = shanghaiReference(new Date(iso)).slice(0, 16); // "YYYY-MM-DD HH:mm"
	assert.equal(formatDateTime(iso), expected);
	assert.equal(toInputValue(iso), expected.replace(" ", "T"));
	assert.equal(formatDate(iso), expected.slice(0, 10));
	assert.equal(formatTime(iso), expected.slice(11, 16));
});

test("daysAgoLocal: 0 is today, N steps back N business-zone days", () => {
	assert.equal(daysAgoLocal(0), todayLocal());
	assert.equal(daysAgoLocal(1), addDays(todayLocal(), -1));
	assert.equal(daysAgoLocal(30), addDays(todayLocal(), -30));
	assert.match(daysAgoLocal(7), /^\d{4}-\d{2}-\d{2}$/);
});

// ---------------------------------------------------------------------------
// toOffsetDateTime
// ---------------------------------------------------------------------------

test("toOffsetDateTime: attaches the fixed business offset", () => {
	assert.equal(toOffsetDateTime("2026-09-28T15:17"), "2026-09-28T15:17:00+08:00");
	assert.equal(toOffsetDateTime("2026-09-28T15:17:30"), "2026-09-28T15:17:30+08:00");
	assert.equal(toOffsetDateTime("2026-09-28T00:00"), "2026-09-28T00:00:00+08:00");
});

test("toOffsetDateTime: blank in, blank out", () => {
	assert.equal(toOffsetDateTime(""), "");
	assert.equal(toOffsetDateTime("   "), "");
});

test("toOffsetDateTime: a bare date becomes the start of that business day", () => {
	assert.equal(toOffsetDateTime("2026-09-28"), "2026-09-28T00:00:00+08:00");
});

test("toOffsetDateTime: already-offset text is not re-interpreted", () => {
	assert.equal(toOffsetDateTime("2026-09-28T15:17:00Z"), "2026-09-28T15:17:00Z");
	assert.equal(
		toOffsetDateTime("2026-09-28T15:17:00+08:00"),
		"2026-09-28T15:17:00+08:00",
	);
});

test("toOffsetDateTime: the pass-through fallback returns trimmed text (F4)", () => {
	assert.equal(toOffsetDateTime("  x  "), "x");
	assert.equal(toOffsetDateTime("  garbage  "), "garbage");
	assert.equal(toOffsetDateTime("\t2026-09-28T15:17:00Z\n"), "2026-09-28T15:17:00Z");
});

test("toOffsetDateTime: a fraction without seconds is not guessed at (F6)", () => {
	// "15:17.123" is not a valid datetime-local value; leave it for the backend.
	assert.equal(toOffsetDateTime("2026-09-28T15:17.123456"), "2026-09-28T15:17.123456");
});

test("toOffsetDateTime: a fraction after explicit seconds is preserved", () => {
	assert.equal(
		toOffsetDateTime("2026-09-28T15:17:30.123456"),
		"2026-09-28T15:17:30.123456+08:00",
	);
	assert.equal(toOffsetDateTime("2026-09-28T15:17:30.5"), "2026-09-28T15:17:30.5+08:00");
});

// ---------------------------------------------------------------------------
// toInputValue
// ---------------------------------------------------------------------------

test("toInputValue: instant -> business-zone datetime-local value", () => {
	assert.equal(toInputValue("2026-09-28T07:17:00Z"), "2026-09-28T15:17");
	assert.equal(toInputValue("2026-09-28T07:17:00+08:00"), "2026-09-28T07:17");
	assert.equal(toInputValue("2026-06-30T16:00:00Z"), "2026-07-01T00:00");
});

test("toInputValue: round-trips with toOffsetDateTime", () => {
	const input = toInputValue("2026-09-28T07:17:00Z");
	assert.equal(input, "2026-09-28T15:17");
	assert.equal(toOffsetDateTime(input), "2026-09-28T15:17:00+08:00");
	assert.equal(formatDateTime(toOffsetDateTime(input)), "2026-09-28 15:17");
});

test("toInputValue: blank and date-only handling", () => {
	assert.equal(toInputValue(""), "");
	assert.equal(toInputValue(null), "");
	assert.equal(toInputValue(undefined), "");
	assert.equal(toInputValue("   "), "");
	// F2 does not apply here: a datetime-local widget requires a time component.
	assert.equal(toInputValue("2026-09-28"), "2026-09-28T00:00");
	assert.equal(toInputValue("2026-07-01"), "2026-07-01T00:00");
});

test("toInputValue: offset-less wall clock is passed through", () => {
	assert.equal(toInputValue("2026-09-28T15:17"), "2026-09-28T15:17");
	assert.equal(toInputValue("2026-09-28T15:17:30"), "2026-09-28T15:17");
});

// ---------------------------------------------------------------------------
// dayBoundary
// ---------------------------------------------------------------------------

test("dayBoundary: inclusive business-day bounds", () => {
	assert.equal(dayBoundary("2026-09-28", "start"), "2026-09-28T00:00:00+08:00");
	assert.equal(dayBoundary("2026-09-28", "end"), "2026-09-28T23:59:59+08:00");
});

test("dayBoundary: instants on the boundary land inside the day", () => {
	const start = "2026-09-28T00:00:00+08:00";
	const end = "2026-09-28T23:59:59+08:00";
	assert.equal(dayBoundary(start.slice(0, 10), "start"), start);
	assert.equal(dayBoundary(end.slice(0, 10), "end"), end);
	assert.equal(formatDate(start), "2026-09-28");
	assert.equal(formatDate(end), "2026-09-28");
});

test("dayBoundary: a datetime is returned unchanged, never widened to a day (F3)", () => {
	// Truncating would silently turn a datetime filter into a whole-day filter.
	// Left offset-less on purpose so the backend rejects it with a loud 400.
	assert.equal(dayBoundary("2026-09-28T15:17", "start"), "2026-09-28T15:17");
	assert.equal(dayBoundary("2026-09-28T15:17", "end"), "2026-09-28T15:17");
	assert.equal(dayBoundary("2026-09-28T15:17:30", "start"), "2026-09-28T15:17:30");
	assert.equal(dayBoundary("2026-09-28 15:17", "start"), "2026-09-28 15:17");
});

test("dayBoundary: unparseable input is echoed back trimmed", () => {
	assert.equal(dayBoundary("", "start"), "");
	assert.equal(dayBoundary("", "end"), "");
	assert.equal(dayBoundary("not-a-date", "end"), "not-a-date");
});

test("pass-through fallbacks normalise identically to trimmed text", () => {
	// Both fallback branches of the single time boundary must agree, otherwise the
	// module reintroduces the "several conventions in one codebase" defect it
	// exists to remove.
	assert.equal(dayBoundary("  x  ", "start"), "x");
	assert.equal(dayBoundary("  x  ", "end"), "x");
	assert.equal(toOffsetDateTime("  x  "), "x");
	assert.equal(dayBoundary("  x  ", "start"), toOffsetDateTime("  x  "));
	assert.equal(dayBoundary("  garbage  ", "end"), toOffsetDateTime("  garbage  "));
	// An unparseable datetime is a fallback for BOTH helpers, so it must echo the
	// same trimmed text. (A *valid* wall clock legitimately differs: toOffsetDateTime
	// converts it, dayBoundary passes it through.)
	assert.equal(
		dayBoundary("\t 2026-09-28T15:17.123 \n", "start"),
		toOffsetDateTime("\t 2026-09-28T15:17.123 \n"),
	);
	assert.equal(dayBoundary("\t 2026-09-28T15:17.123 \n", "start"), "2026-09-28T15:17.123");
	// ...and they still agree that blank is blank.
	assert.equal(dayBoundary("   ", "start"), "");
	assert.equal(toOffsetDateTime("   "), "");
});

test("dayBoundary: surrounding whitespace on a date-only value is tolerated", () => {
	assert.equal(dayBoundary(" 2026-09-28 ", "start"), "2026-09-28T00:00:00+08:00");
	assert.equal(dayBoundary(" 2026-09-28 ", "end"), "2026-09-28T23:59:59+08:00");
});

test("dayBoundary: output parses back through formatDate (round trip)", () => {
	assert.equal(formatDate(dayBoundary("2026-09-28", "start")), "2026-09-28");
	assert.equal(formatDate(dayBoundary("2026-09-28", "end")), "2026-09-28");
	assert.equal(formatTime(dayBoundary("2026-09-28", "start")), "00:00");
	assert.equal(formatTime(dayBoundary("2026-09-28", "end")), "23:59");
});

// ---------------------------------------------------------------------------
// addDays
// ---------------------------------------------------------------------------

test("addDays: basic arithmetic across month and year ends", () => {
	assert.equal(addDays("2026-12-31", 1), "2027-01-01");
	assert.equal(addDays("2026-01-01", -1), "2025-12-31");
	assert.equal(addDays("2026-09-28", 7), "2026-10-05");
	assert.equal(addDays("2026-09-28", 0), "2026-09-28");
});

test("addDays: the year stays zero-padded to four digits (F5)", () => {
	assert.equal(addDays("0099-01-01", 0), "0099-01-01");
	assert.equal(addDays("0099-12-31", 1), "0100-01-01");
	assert.equal(addDays("0100-01-01", -1), "0099-12-31");
	assert.equal(addDays("2026-09-28", 1), "2026-09-29");
});

test("addDays: month lengths and leap years are calendar-correct", () => {
	assert.equal(addDays("2026-03-01", -1), "2026-02-28");
	assert.equal(addDays("2028-02-28", 1), "2028-02-29");
	assert.equal(addDays("2028-03-01", -1), "2028-02-29");
	assert.equal(addDays("2026-04-30", 1), "2026-05-01");
});

test("addDays: invalid input is returned unchanged", () => {
	assert.equal(addDays("", 1), "");
	assert.equal(addDays("2026-02-30", 1), "2026-02-30");
	assert.equal(addDays("2026-09-28T07:17", 1), "2026-09-28T07:17");
});

// ---------------------------------------------------------------------------
// weekStartOf
// ---------------------------------------------------------------------------

test("weekStartOf: Monday is the first day of the week", () => {
	assert.equal(weekStartOf("2026-09-28"), "2026-09-28"); // Monday
	assert.equal(weekStartOf("2026-10-01"), "2026-09-28"); // Thursday
	assert.equal(weekStartOf("2026-10-04"), "2026-09-28"); // Sunday
	assert.equal(weekStartOf("2026-10-05"), "2026-10-05"); // next Monday
});

test("weekStartOf: result is always a Monday, in the same week", () => {
	for (const day of ["2026-09-28", "2026-10-01", "2026-10-04", "2027-01-01"]) {
		const monday = weekStartOf(day);
		assert.equal(addDays(monday, 0), monday);
		assert.equal(new Date(`${monday}T00:00:00Z`).getUTCDay(), 1, `${monday} is not a Monday`);
		// The requested day is at most 6 days after its Monday.
		assert.ok(addDays(monday, 6) >= day, `${day} is not inside the week of ${monday}`);
		assert.ok(addDays(monday, -1) < day, `${day} is not inside the week of ${monday}`);
	}
});

test("weekStartOf: invalid input is returned unchanged", () => {
	assert.equal(weekStartOf(""), "");
	assert.equal(weekStartOf("2026-09-28T07:17"), "2026-09-28T07:17");
});

// ---------------------------------------------------------------------------
// module contract
// ---------------------------------------------------------------------------

test("BUSINESS_TIME_ZONE is pinned to Asia/Shanghai", () => {
	assert.equal(BUSINESS_TIME_ZONE, "Asia/Shanghai");
});

test("host-timezone independence: a fixed instant renders identically everywhere", () => {
	const instant = "2026-09-28T07:17:00Z";
	// These hold whatever TZ the runner sets; the reference below is Shanghai.
	const reference = shanghaiReference(new Date(instant));
	assert.equal(reference, "2026-09-28 15:17:00");
	assert.equal(formatDateTime(instant), "2026-09-28 15:17");
	assert.equal(formatDate(instant), "2026-09-28");
	assert.equal(formatTime(instant), "15:17");
	assert.equal(toInputValue(instant), "2026-09-28T15:17");
});
