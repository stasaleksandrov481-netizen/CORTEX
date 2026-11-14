create table public.cortex_voice_packs (
    id text primary key check (id ~ '^[a-z0-9][a-z0-9-]{1,47}$' and id not in ('android', 'ruvoice-eugene', 'ruvoice-aidar', 'ruvoice-xenia')),
    title text not null check (char_length(btrim(title)) between 1 and 80),
    description text not null default '' check (char_length(description) <= 220),
    kind text not null check (kind in ('zip', 'ruvoice-preset')),
    version integer not null default 1 check (version between 1 and 1000000),
    url text not null check (
        char_length(url) <= 1400 and url ~ '^https://github[.]com/[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+/releases/download/[^/?#]+/[^/?#]+$'
    ),
    size_bytes integer not null check (size_bytes between 1 and 170000000),
    sha256 text not null check (sha256 ~ '^[a-f0-9]{64}$'),
    published boolean not null default false,
    check (kind <> 'ruvoice-preset' or size_bytes <= 1500000)
);

alter table public.cortex_voice_packs enable row level security;
revoke all on public.cortex_voice_packs from public, anon, authenticated;
grant select on public.cortex_voice_packs to anon, authenticated;
grant insert, update on public.cortex_voice_packs to authenticated;

create policy "Published voices are readable"
on public.cortex_voice_packs for select to anon, authenticated
using (published);

create policy "Voice catalog is managed by CORTEX staff"
on public.cortex_voice_packs for all to authenticated
using (
    (select auth.jwt() ->> 'aal') = 'aal2'
    and exists (
        select 1 from public.cortex_admin_roles as staff
        where staff.user_id = (select auth.uid()) and staff.role in ('owner', 'admin')
    )
)
with check (
    (select auth.jwt() ->> 'aal') = 'aal2'
    and exists (
        select 1 from public.cortex_admin_roles as staff
        where staff.user_id = (select auth.uid()) and staff.role in ('owner', 'admin')
    )
);
