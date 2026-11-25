-- Coach Q&A conversation threads persisted across devices for MyRunCoach.
-- Safe to run idempotently on existing project `running-tracker` (`mwjurzrnkpnqsfqrhaex`).

create table if not exists public.coach_threads (
    id text primary key,
    title text not null,
    category text not null default 'free',
    run_id text,
    model text,
    messages jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_coach_threads_updated_at
    on public.coach_threads (updated_at desc);

create or replace function public.set_coach_threads_updated_at()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists trg_coach_threads_updated_at on public.coach_threads;
create trigger trg_coach_threads_updated_at
    before update on public.coach_threads
    for each row
    execute function public.set_coach_threads_updated_at();

grant usage on schema public to anon, authenticated;
grant select, insert, update, delete on table public.coach_threads to anon, authenticated;

alter table public.coach_threads enable row level security;

drop policy if exists "Allow full access for personal coach threads" on public.coach_threads;
create policy "Allow full access for personal coach threads"
    on public.coach_threads
    for all
    to anon, authenticated
    using (true)
    with check (true);
