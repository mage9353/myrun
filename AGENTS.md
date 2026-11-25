# MyRun Workspace Rules

This monorepo (`e:\vscode_temp\MyRun`, GitHub repository `https://github.com/mage9353/myrun`) contains two apps sharing one Supabase PostgreSQL database (`running-tracker`, project ref `mwjurzrnkpnqsfqrhaex`):

1. **`MyRunSync/`**: Android Kotlin app (`com.myrunsync`) that reads running workouts from Android Health Connect (e.g., Samsung Health) and upserts summary metrics + 1-minute downsampled telemetry into Supabase. Keep the name `MyRunSync`.
2. **`MyRunCoach/`**: Single-file static web dashboard (`index.html`, deployed to `https://mage9353.github.io/myrun/` via `.github/workflows/deploy-pages.yml`) that visualizes running logs and calls the Gemini API for coaching. Keep the name `MyRunCoach`.
3. **`supabase/migrations/001_running_logs_schema.sql`**: Single source of truth for the database schema.

## Shared Database Rules
- **Always use `public.running_logs`** as the canonical table name. Do NOT create separate tables such as `running_sessions`.
- **Primary key**: `id` (`bigint` identity).
- **Upsert conflict key**: `hc_session_id` (`text not null unique`, constraint `running_logs_hc_session_id_key`).
- **Required NOT NULL columns**: `hc_session_id`, `run_date` (`timestamptz`), `workout_type` (`'야외'` or `'트레드밀'`), `distance_km`, `duration_sec`, `timeseries_1min`.
- **Integer columns**: `duration_sec`, `avg_pace_sec`, `best_pace_sec`, `avg_hr`, `max_hr`, `min_hr`, `zone2_min_bpm`, `zone2_max_bpm`, `avg_cadence`, `max_cadence`, `calories`, and `avg_power_w` must be serialized as integers from `MyRunSync`.
- **Additional Samsung Health metrics**: `altitude_gain`, `altitude_loss`, `vo2_max` (numeric, nullable).
- **1-minute JSONB column**: `timeseries_1min` containing objects with keys:
  - `minute` (1-based integer), `offset_sec` (0-based integer), `timestamp` (ISO-8601 UTC string)
  - `hr` (rounded avg bpm, int?), `max_hr` (int?), `min_hr` (int?)
  - `zone2_ratio` (0.0..1.0), `pace_sec` (rounded sec/km, int?), `cadence` (rounded spm, int?), `distance_m` (double), `altitude_m` (double?)
- Whenever changing columns or `timeseries_1min` keys, update Supabase (`mwjurzrnkpnqsfqrhaex`), `supabase/migrations/001_running_logs_schema.sql`, `MyRunSync`, and `MyRunCoach` together.
