-- Run with a recent official trace_processor or in ui.perfetto.dev.
-- Inspect support/data loss FIRST. Missing events mean unknown, never zero.
SELECT name, value, severity FROM stats WHERE value != 0 AND severity IN ('error', 'data_loss');

INCLUDE PERFETTO MODULE android.suspend;
SELECT power_state, SUM(dur) / 1e9 AS seconds FROM android_suspend_state GROUP BY power_state;

-- Markers identify app work at fix/draw boundaries, with no periodic instrumentation.
SELECT s.name, s.ts, s.dur, p.name AS process
FROM slice s JOIN thread_track tt ON tt.id = s.track_id
JOIN thread t USING (utid) JOIN process p USING (upid)
WHERE s.name IN ('Cairn.fix', 'Cairn.map.draw') ORDER BY s.ts;

-- Wakeup causes need attribution; a wakeup near a fix is not proof it was GNSS.
INCLUDE PERFETTO MODULE android.wakeups;
SELECT * FROM android_wakeups ORDER BY ts;

-- App-owned wake locks. Platform GNSS locks must be inspected separately.
INCLUDE PERFETTO MODULE android.app_wakelocks;
SELECT * FROM android_app_wakelocks ORDER BY ts;

-- App frame durations (nanoseconds). Exclude SurfaceFlinger/compositor frames.
SELECT p.name, f.ts, f.dur, f.jank_type
FROM actual_frame_timeline_slice f JOIN process p USING (upid)
WHERE p.name = 'app.cairn.trek' ORDER BY f.ts;
