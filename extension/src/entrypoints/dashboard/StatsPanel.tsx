import type { DashboardState, UsageByDay } from '../../core/protocol';
import { formatDuration } from '../../ui/format';
import { Card } from '../../ui/components';

/** Sum active seconds for every domain on `day`; 0 when the day has no rollup yet. */
function dayActiveSeconds(usage: UsageByDay, day: string): number {
  const dayUsage = usage[day];
  if (!dayUsage) return 0;
  return Object.values(dayUsage).reduce((sum, d) => sum + d.activeSec, 0);
}

function dayCounts(usage: UsageByDay, day: string): { blocked: number; walkedAway: number } {
  const dayUsage = usage[day];
  if (!dayUsage) return { blocked: 0, walkedAway: 0 };
  let blocked = 0;
  let walkedAway = 0;
  for (const d of Object.values(dayUsage)) {
    blocked += d.blocked;
    walkedAway += d.walkedAway;
  }
  return { blocked, walkedAway };
}

function hourlyTotals(usage: UsageByDay, days: string[]): number[] {
  const hours = new Array<number>(24).fill(0);
  for (const day of days) {
    const dayUsage = usage[day];
    if (!dayUsage) continue;
    for (const d of Object.values(dayUsage)) {
      d.hourly.forEach((v, h) => {
        hours[h] = (hours[h] ?? 0) + v;
      });
    }
  }
  return hours;
}

function domainTotals(usage: UsageByDay, days: string[]): { domain: string; activeSec: number }[] {
  const totals: Record<string, number> = {};
  for (const day of days) {
    const dayUsage = usage[day];
    if (!dayUsage) continue;
    for (const [domain, d] of Object.entries(dayUsage)) {
      totals[domain] = (totals[domain] ?? 0) + d.activeSec;
    }
  }
  return Object.entries(totals)
    .map(([domain, activeSec]) => ({ domain, activeSec }))
    .sort((a, b) => b.activeSec - a.activeSec);
}

function weekdayLabel(day: string): string {
  // `day` is a local yyyy-mm-dd key; parse as local (not UTC) to avoid an off-by-one.
  const parts = day.split('-').map(Number);
  const [y, m, d] = parts as [number, number, number];
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { weekday: 'short' });
}

export function StatsPanel({ data }: { data: DashboardState }) {
  const { usage, recentDays, allTimeBlocked, allTimeWalkedAway } = data;
  const todayKey = recentDays[recentDays.length - 1] ?? '';
  const todayActive = dayActiveSeconds(usage, todayKey);
  const todayCounts = dayCounts(usage, todayKey);

  const dayValues = recentDays.map((day) => ({
    day,
    active: dayActiveSeconds(usage, day),
    ...dayCounts(usage, day),
  }));
  const maxDay = Math.max(1, ...dayValues.map((d) => d.active));

  const hours = hourlyTotals(usage, recentDays);
  const maxHour = Math.max(1, ...hours);

  const topSites = domainTotals(usage, recentDays).slice(0, 8);
  const maxSite = Math.max(1, ...topSites.map((s) => s.activeSec));

  const isAllZero = todayActive === 0 && maxDay === 1 && topSites.length === 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 16 }}>
        <Card title="Today">
          <p style={{ margin: 0, fontSize: 32, fontWeight: 700 }}>{formatDuration(todayActive)}</p>
          <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            screen time
          </p>
        </Card>
        <Card title="Blocked">
          <p style={{ margin: 0, fontSize: 32, fontWeight: 700 }}>{todayCounts.blocked}</p>
          <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            today · {allTimeBlocked} all-time
          </p>
        </Card>
        <Card title="Walked Away">
          <p style={{ margin: 0, fontSize: 32, fontWeight: 700 }}>{todayCounts.walkedAway}</p>
          <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            today · {allTimeWalkedAway} all-time
          </p>
        </Card>
      </div>

      {isAllZero && (
        <Card>
          <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            No activity tracked yet. Stats fill in as you browse.
          </p>
        </Card>
      )}

      <Card title="Last 7 days">
        <svg
          role="img"
          aria-label="7-day screen time bar chart"
          viewBox="0 0 280 110"
          width="100%"
          height={140}
          preserveAspectRatio="xMidYMid meet"
        >
          {dayValues.map((d, i) => {
            const barWidth = 28;
            const gap = (280 - barWidth * 7) / 8;
            const x = gap + i * (barWidth + gap);
            const maxBarHeight = 80;
            const height = Math.max(2, (d.active / maxDay) * maxBarHeight);
            const y = 90 - height;
            return (
              <g key={d.day}>
                <rect
                  x={x}
                  y={y}
                  width={barWidth}
                  height={height}
                  rx={4}
                  fill="var(--nudge-primary)"
                  opacity={d.active === 0 ? 0.25 : 1}
                />
                <text
                  x={x + barWidth / 2}
                  y={104}
                  textAnchor="middle"
                  fontSize={10}
                  fill="var(--nudge-on-surface-variant)"
                >
                  {weekdayLabel(d.day)}
                </text>
              </g>
            );
          })}
        </svg>
      </Card>

      <Card title="Hourly activity">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(24, 1fr)', gap: 3 }}>
          {hours.map((v, h) => {
            const intensity = maxHour === 0 ? 0 : v / maxHour;
            return (
              <div
                key={h}
                title={`${h}:00 — ${formatDuration(v)}`}
                style={{
                  aspectRatio: '1 / 1',
                  borderRadius: 3,
                  background:
                    intensity === 0
                      ? 'var(--nudge-surface-variant)'
                      : `color-mix(in srgb, var(--nudge-primary) ${Math.round(20 + intensity * 80)}%, var(--nudge-surface-variant))`,
                }}
              />
            );
          })}
        </div>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            marginTop: 6,
            fontSize: 10,
            color: 'var(--nudge-on-surface-variant)',
          }}
        >
          <span>12am</span>
          <span>12pm</span>
          <span>11pm</span>
        </div>
      </Card>

      <Card title="Top sites">
        {topSites.length === 0 ? (
          <p style={{ margin: 0, fontSize: 13, color: 'var(--nudge-on-surface-variant)' }}>
            Nothing tracked in the last 7 days.
          </p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {topSites.map((s) => (
              <div key={s.domain}>
                <div
                  style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    fontSize: 13,
                    marginBottom: 4,
                  }}
                >
                  <span>{s.domain}</span>
                  <span style={{ color: 'var(--nudge-on-surface-variant)' }}>
                    {formatDuration(s.activeSec)}
                  </span>
                </div>
                <div
                  style={{
                    height: 6,
                    borderRadius: 3,
                    background: 'var(--nudge-surface-variant)',
                    overflow: 'hidden',
                  }}
                >
                  <div
                    style={{
                      height: '100%',
                      width: `${Math.max(2, (s.activeSec / maxSite) * 100)}%`,
                      background: 'var(--nudge-primary)',
                      borderRadius: 3,
                    }}
                  />
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
