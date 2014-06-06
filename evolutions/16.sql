-- This evolution:
-- - Replaces FlagCopyVio and Illegal with FlagInapt
-- - Adds hidden/deleted-at/-by columns, and Undelete actions
-- - Adds ClearFlag action


# --- !Ups


alter table DW1_PAGES add column DELETED_AT timestamp;
alter table DW1_PAGES add column DELETED_BY_ID varchar(32);

alter table DW1_POSTS add column POST_HIDDEN_AT timestamp;
alter table DW1_POSTS add column POST_HIDDEN_BY_ID varchar(32);
alter table DW1_POSTS add column POST_DELETED_BY_ID varchar(32);
alter table DW1_POSTS add column TREE_DELETED_BY_ID varchar(32);

alter table DW1_PAGE_ACTIONS add column DELETED_AT timestamp;
alter table DW1_PAGE_ACTIONS add column DELETED_BY_ID varchar(32);


alter table DW1_PAGE_ACTIONS drop constraint DW1_PGAS_TYPE__C_IN;

update DW1_PAGE_ACTIONS set TYPE = 'FlagInapt' where TYPE in ('FlagIllegal', 'FlagCopyVio');

alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_TYPE__C_IN check (TYPE in (
  'Post', 'Edit', 'EditApp',
  'Aprv', 'Rjct',
  'VoteLike', 'VoteWrong', 'VoteOffTopic',
  'PinAtPos', 'PinVotes',
  'MoveTree',
  'CollapsePost', 'CollapseTree', 'CloseTree', 'Reopen', -- <-- Rename 'ReopenTree' to 'Reopen'
  'HidePost', 'Unhide',                  -- <-- Add 'HidePost' and 'Unhide'.
  'DelPost', 'DelTree', 'Undelete',      -- <-- add 'Undelete'
  'FlagSpam', 'FlagInapt', 'FlagOther',  -- <-- replace 'FlagIllegal' and 'CopyVio' with 'Inapt'
  'ClearFlags'));                        -- <-- add 'ClearFlags'
                                         -- <-- remove 'Undo'


# --- !Downs


alter table DW1_PAGES drop column DELETED_AT;
alter table DW1_PAGES drop column DELETED_BY_ID;

alter table DW1_POSTS drop column POST_HIDDEN_AT;
alter table DW1_POSTS drop column POST_HIDDEN_BY_ID;
alter table DW1_POSTS drop column POST_DELETED_BY_ID;
alter table DW1_POSTS drop column TREE_DELETED_BY_ID;

alter table DW1_PAGE_ACTIONS drop column DELETED_AT;
alter table DW1_PAGE_ACTIONS drop column DELETED_BY_ID;

alter table DW1_PAGE_ACTIONS drop constraint DW1_PGAS_TYPE__C_IN;

-- Cannot go back after having inserted new action types, so simply:
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_TYPE__C_IN check (true);

