-- Per-user dialog state so multi-step conversations survive bot restarts
alter table app_user add column chat_state varchar(32);
