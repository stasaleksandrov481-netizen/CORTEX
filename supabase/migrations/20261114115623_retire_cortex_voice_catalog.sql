create schema if not exists cortex_internal;
revoke all on schema cortex_internal from public, anon, authenticated;

revoke all on public.cortex_voice_packs from public, anon, authenticated;
alter table public.cortex_voice_packs set schema cortex_internal;

notify pgrst, 'reload schema';
