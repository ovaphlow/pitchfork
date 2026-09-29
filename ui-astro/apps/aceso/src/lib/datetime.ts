/**
 * Aceso date/time utility — the single shared time boundary for the app.
 *
 * Every value travelling between the Aceso UI and the Kotlin backend is an
 * instant (`OffsetDateTime`, serialized as a UTC `...Z` string). Every value the
 * operators read or write on screen is a wall clock in the facility's business
 * zone, `Asia/Shanghai`.
 *
 * Rules enforced here:
 *  - Display is always rendered in `BUSINESS_TIME_ZONE`, never in the host or
 *    browser time zone.
 *  - `YYYY-MM-DDTHH:mm[:ss]` input with no offset is read as an Asia/Shanghai
 *    wall clock (that is what `<input type="datetime-local">` produces), never
 *    as host-local time.
 *  - The business offset is the fixed `+08:00` (China has observed no DST since
 *    1991), so boundary values are stable strings, not host-dependent ones.
 *  - Only `Intl` and `Date` are used — no `dayjs`, no `date-fns`.
 *
 * Business-zone wall-clock parts come from `Intl.DateTimeFormat#formatToParts`
 * on purpose: `Date#getFullYear()` / `#getHours()` read the host zone and
 * `toLocaleString()` output must not be parsed back out of a string.
 */

export const BUSINESS_TIME_ZONE = "Asia/Shanghai";

/** China Standard Time is a fixed offset; Asia/Shanghai has had no DST since 1991. */
const BUSINESS_OFFSET = "+08:00";

const DATE_ONLY_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * `datetime-local` shape without any offset or `Z` suffix — i.e. an
 * Asia/Shanghai wall clock. Fractional seconds are tolerated and preserved, but
 * only after an explicit `:ss`: `15:17.123` is not a valid `datetime-local`
 * value, so it is left to the raw pass-through instead of being guessed at.
 */
const WALL_CLOCK_RE =
	/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/;

const MS_PER_DAY = 86_400_000;

const businessPartsFormatter = new Intl.DateTimeFormat("en-US", {
	timeZone: BUSINESS_TIME_ZONE,
	year: "numeric",
	month: "2-digit",
	day: "2-digit",
	hour: "2-digit",
	minute: "2-digit",
	second: "2-digit",
	hourCycle: "h23",
});

type BusinessParts = {
	/** "YYYY-MM-DD" in the business zone. */
	date: string;
	/** "HH:mm" in the business zone. */
	time: string;
};

/** Split an instant into business-zone wall-clock parts (host-TZ independent). */
function businessPartsOf(instant: Date): BusinessParts {
	const parts = businessPartsFormatter.formatToParts(instant);
	const pick = (type: string): string => {
		for (const part of parts) {
			if (part.type === type) return part.value;
		}
		return "";
	};
	const year = pick("year");
	const month = pick("month");
	const day = pick("day");
	const hour = pick("hour");
	const minute = pick("minute");
	return {
		date: `${year}-${month}-${day}`,
		time: `${hour}:${minute}`,
	};
}

type Classified =
	| { kind: "blank" }
	/** "YYYY-MM-DD" — a calendar date with no time component. */
	| { kind: "date"; date: string }
	/** "YYYY-MM-DDTHH:mm[:ss[.fff]]" with no offset — an Asia/Shanghai wall clock. */
	| { kind: "wall"; date: string; time: string; second: string; fraction: string }
	/** Anything else that `new Date` could parse — a real instant. */
	| { kind: "instant"; instant: Date }
	/** Non-blank but unparseable — callers pass the original text through. */
	| { kind: "raw"; raw: string };

/**
 * Classify an incoming value. Order matters: blank, then date-only, then
 * offset-less wall clock, then anything `new Date` accepts.
 *
 * A non-blank value that `new Date` cannot parse is *not* replaced by the
 * fallback — the raw text is passed through so a bad value stays visible.
 */
function classify(value: string | null | undefined): Classified {
	if (value === null || value === undefined) return { kind: "blank" };
	const raw = value.trim();
	if (raw === "") return { kind: "blank" };

	const dateOnly = DATE_ONLY_RE.exec(raw);
	if (dateOnly !== null) {
		return { kind: "date", date: `${dateOnly[1]}-${dateOnly[2]}-${dateOnly[3]}` };
	}

	const wall = WALL_CLOCK_RE.exec(raw);
	if (wall !== null) {
		return {
			kind: "wall",
			date: `${wall[1]}-${wall[2]}-${wall[3]}`,
			time: `${wall[4]}:${wall[5]}`,
			second: wall[6] ?? "",
			fraction: wall[7] ?? "",
		};
	}

	const instant = new Date(raw);
	if (Number.isNaN(instant.getTime())) return { kind: "raw", raw: value };
	return { kind: "instant", instant };
}

function pad2(value: number): string {
	return String(value).padStart(2, "0");
}

type DateOnly = { year: number; month: number; day: number; iso: string; ms: number };

/**
 * Parse a strict "YYYY-MM-DD" into UTC milliseconds without going through the
 * host time zone. Rejects impossible dates such as "2026-02-30".
 */
function parseDateOnly(raw: string): DateOnly | null {
	const match = DATE_ONLY_RE.exec(raw);
	if (match === null) return null;
	const year = Number(match[1]);
	const month = Number(match[2]);
	const day = Number(match[3]);
	const probe = new Date(0);
	probe.setUTCHours(0, 0, 0, 0);
	// setUTCFullYear (not Date.UTC) so years below 100 are not remapped to 19xx.
	probe.setUTCFullYear(year, month - 1, day);
	if (
		probe.getUTCFullYear() !== year ||
		probe.getUTCMonth() !== month - 1 ||
		probe.getUTCDate() !== day
	) {
		return null;
	}
	return { year, month, day, iso: `${match[1]}-${match[2]}-${match[3]}`, ms: probe.getTime() };
}

/**
 * Instant -> "YYYY-MM-DD HH:mm" (business zone). Fallback `empty` for
 * null/undefined/blank.
 *
 * Plan-conformance note: a date-only value renders as the bare date
 * (`"2026-07-01"`), NOT as `"2026-07-01 00:00"`. No `DATE` column reaches this
 * function today, but a date has no time of day in the data, and clinical
 * records must not display a midnight that nobody recorded. This also preserves
 * the exact rendering the app had before the local-time migration.
 * (`toInputValue` still expands a date to `"…T00:00"` because the input widget
 * requires a time component.)
 */
export function formatDateTime(value: string | null | undefined, empty = "-"): string {
	const parsed = classify(value);
	switch (parsed.kind) {
		case "blank":
			return empty;
		case "date":
			// A calendar date carries no time of day, so never invent a midnight:
			// this matches the pre-migration rendering of DATE columns exactly.
			return parsed.date;
		case "wall":
			return `${parsed.date} ${parsed.time}`;
		case "instant": {
			const parts = businessPartsOf(parsed.instant);
			return `${parts.date} ${parts.time}`;
		}
		case "raw":
			return parsed.raw;
	}
}

/**
 * Date-only "YYYY-MM-DD" passes through unchanged. Instant -> business-zone
 * calendar date. Fallback `empty` for null/undefined/blank.
 */
export function formatDate(value: string | null | undefined, empty = "-"): string {
	const parsed = classify(value);
	switch (parsed.kind) {
		case "blank":
			return empty;
		case "date":
			return parsed.date;
		case "wall":
			return parsed.date;
		case "instant":
			return businessPartsOf(parsed.instant).date;
		case "raw":
			return parsed.raw;
	}
}

/** Instant -> "HH:mm" (business zone). Date-only input -> `empty`. */
export function formatTime(value: string | null | undefined, empty = "-"): string {
	const parsed = classify(value);
	switch (parsed.kind) {
		case "blank":
			return empty;
		case "date":
			// A calendar date carries no time of day.
			return empty;
		case "wall":
			return parsed.time;
		case "instant":
			return businessPartsOf(parsed.instant).time;
		case "raw":
			return parsed.raw;
	}
}

/** Business-zone "today" as "YYYY-MM-DD". */
export function todayLocal(): string {
	return businessPartsOf(new Date()).date;
}

/** Business-zone now as a `datetime-local` value "YYYY-MM-DDTHH:mm". */
export function nowLocalInput(): string {
	const parts = businessPartsOf(new Date());
	return `${parts.date}T${parts.time}`;
}

/** Business-zone today minus `days` as "YYYY-MM-DD". */
export function daysAgoLocal(days: number): string {
	return addDays(todayLocal(), -days);
}

/**
 * `datetime-local` text -> OffsetDateTime text, always with the business offset.
 * "2026-09-28T15:17" -> "2026-09-28T15:17:00+08:00"; "" -> "".
 *
 * Seconds are preserved when the caller supplied them. Built by string
 * composition on purpose: `new Date(localInput)` would read the host zone.
 * Anything that is not an offset-less wall clock or a bare date is returned
 * unchanged so the backend rejects it loudly instead of us guessing.
 */
export function toOffsetDateTime(localInput: string): string {
	const raw = localInput.trim();
	if (raw === "") return "";

	const wall = WALL_CLOCK_RE.exec(raw);
	if (wall !== null) {
		const second = wall[6] === undefined ? "00" : wall[6];
		const fraction = wall[7] === undefined ? "" : `.${wall[7]}`;
		return `${wall[1]}-${wall[2]}-${wall[3]}T${wall[4]}:${wall[5]}:${second}${fraction}${BUSINESS_OFFSET}`;
	}

	const dateOnly = DATE_ONLY_RE.exec(raw);
	if (dateOnly !== null) {
		return `${dateOnly[1]}-${dateOnly[2]}-${dateOnly[3]}T00:00:00${BUSINESS_OFFSET}`;
	}

	return raw;
}

/** Instant -> `datetime-local` value in the business zone ("YYYY-MM-DDTHH:mm"). "" for blank. */
export function toInputValue(value: string | null | undefined): string {
	const parsed = classify(value);
	switch (parsed.kind) {
		case "blank":
			return "";
		case "date":
			return `${parsed.date}T00:00`;
		case "wall":
			return `${parsed.date}T${parsed.time}`;
		case "instant": {
			const parts = businessPartsOf(parsed.instant);
			return `${parts.date}T${parts.time}`;
		}
		case "raw":
			return parsed.raw;
	}
}

/**
 * "YYYY-MM-DD" -> inclusive day boundary instant with the business offset.
 * edge "start" -> "YYYY-MM-DDT00:00:00+08:00"; edge "end" -> "YYYY-MM-DDT23:59:59+08:00".
 *
 * The input must be a bare date (`YYYY-MM-DD`, e.g. a `date` input's value,
 * optionally padded with whitespace). Anything else — notably a `datetime-local`
 * value — is echoed back **trimmed** rather than truncated: silently widening
 * `"2026-09-28T15:17"` to a whole day would filter the wrong rows, whereas the
 * offset-less text is rejected by the backend's `OffsetDateTime.parse` with a
 * loud 400. The pass-through echoes the trimmed text so that both fallback
 * branches of this module normalise identically. Blank in, blank out.
 */
export function dayBoundary(date: string, edge: "start" | "end"): string {
	const raw = date.trim();
	// DATE_ONLY_RE anchors both ends, so `raw` is already the canonical date.
	if (DATE_ONLY_RE.exec(raw) === null) return raw;
	return edge === "end"
		? `${raw}T23:59:59${BUSINESS_OFFSET}`
		: `${raw}T00:00:00${BUSINESS_OFFSET}`;
}

/** TZ-independent date-only calendar arithmetic. "2026-12-31", 1 -> "2027-01-01". */
export function addDays(date: string, days: number): string {
	const parsed = parseDateOnly(date.trim());
	if (parsed === null) return date;
	const shifted = new Date(parsed.ms + days * MS_PER_DAY);
	// Years <= 0 are out of contract (no `type="date"` input, no backend
	// `LocalDate.toString()` and no stored row can produce one) and are
	// intentionally not guarded: the guard would be unreachable code.
	const year = String(shifted.getUTCFullYear()).padStart(4, "0");
	return `${year}-${pad2(shifted.getUTCMonth() + 1)}-${pad2(shifted.getUTCDate())}`;
}

/**
 * Monday-of-week for a date-only string, TZ-independent.
 * weekStartOf("2026-10-01") -> "2026-09-28" (2026-09-28 is a Monday).
 */
export function weekStartOf(date: string): string {
	const parsed = parseDateOnly(date.trim());
	if (parsed === null) return date;
	const probe = new Date(parsed.ms);
	// getUTCDay(): 0 = Sunday ... 6 = Saturday; shift so Monday is 0.
	const mondayOffset = (probe.getUTCDay() + 6) % 7;
	return addDays(parsed.iso, -mondayOffset);
}
