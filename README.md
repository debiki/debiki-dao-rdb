Old repo. Forget this. Do not use.

This was Talkyard's Database Access Object (DAO) for relational databases
(well, only Postgres), placed in a sub modlue, in case I would add other DAOs
for other databases — then I could do that in other submodules.

But that won't happen the nearest time. And doing some development in this
submodule repo, and some development in the main repo, is cumbersome — takes
time to synchronize changes between the two repos.

So I'm now moving this repo's source code directly into Talkyard's main repo
instead, i.e. https://github.com/debiki/talkyard, to directory
modules/ty-dao-rdb/.


