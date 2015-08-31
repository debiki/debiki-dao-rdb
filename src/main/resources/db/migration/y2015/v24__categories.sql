
-- Create categories table
------------------------------------------------------

create table dw2_categories(
  site_id varchar not null,
  id int not null,
  parent_id int,  -- FK
  position int,
  name varchar not null,
  slug varchar not null,  -- FK
  about_topic_id varchar,  -- FK + ix
  description varchar,
  created_at timestamp not null,
  updated_at timestamp not null,
  locked_at timestamp,
  frozen_at timestamp,
  deleted_at timestamp,
  new_topic_types varchar, -- comma separated topic type list, like: 5,3,11
  num_topics int not null default 0,
  num_posts int not null default 0,

  constraint dw2_cats_id__p primary key (site_id, id),

  constraint dw2_cats__r__cats foreign key (site_id, parent_id)
    references dw2_categories(site_id, id), -- ix: dw2_cats_site_parent__i

  constraint dw2_cats__r__pages foreign key (site_id, about_topic_id)
    references dw1_pages(site_id, page_id), -- ix: dw2_cats_site_abouttopic__i

  constraint dw2_cats_name__c_len check(length(name) between 1 and 100),
  constraint dw2_cats_slug__c_len check(length(slug) between 1 and 100),
  constraint dw2_cats_description__c_len check(length(description) < 1000),
  constraint dw2_cats_created_updated__c_le check(created_at <= updated_at),
  constraint dw2_cats_newtopictypes__c check(new_topic_types ~ '[0-9,]'),
  constraint dw2_cats_numtopics__c_gez check(num_topics >= 0),
  constraint dw2_cats_numposts__c_gez check(num_posts >= 0),
  constraint dw2_cats_numtopics_numposts__c_le check(num_topics <= num_posts)
);


create index dw2_cats_site_parent__i on dw2_categories(site_id, parent_id);
create index dw2_cats_site_abouttopic__i on dw2_categories(site_id, about_topic_id);
create index dw2_cats_site_slug__i on dw2_categories(site_id, slug);


-- Create categories for www.effectivediscussions.org:
------------------------------------------------------

insert into dw2_categories
    select '3', 1, null, null, 'Uncategorized', 'uncategorized', null, null, now_utc(), now_utc()
    from dw1_pages where site_id = '3' and page_id = '4jqu3';

insert into dw2_categories
    select '3', 2, 1, 10, 'Sandbox', 'sandbox', '2', '', now_utc(), now_utc()
    from dw1_pages where site_id = '3' and page_id = '4jqu3';

insert into dw2_categories
    select '3', 3, 1,  5, 'General', 'general', '1d8z5', '', now_utc(), now_utc()
    from dw1_pages where site_id = '3' and page_id = '4jqu3';

insert into dw2_categories
    select '3', 4, 1,  20, 'Pages', 'pages', '110j7', '', now_utc(), now_utc()
    from dw1_pages where site_id = '3' and page_id = '4jqu3';


-- Make dw1_pages use categories not parent page ids:
------------------------------------------------------

alter table dw1_pages drop constraint dw1_pages_parentpage__r__pages;

-- Link topics to categories at www.effectivediscussions.org:
alter table dw1_pages alter column parent_page_id type int using (
    case site_id
        when '3' then
            case parent_page_id
                when '2' then 2      -- Sandbox
                when '1d8z5' then 3  -- the General category
                when '110j7' then 4  -- Pages
                else null
            end
        else null
    end);
alter table dw1_pages rename column parent_page_id to category_id;
alter table dw1_pages add constraint dw1_pages_category__r__categories
    foreign key (site_id, category_id) references dw2_categories(site_id, id);

-- Could add constraint that each forum page links to a category

alter index dw1_pages_parentid_about rename to dw1_pages_category_about__u;
alter index dw1_pages_tnt_parentpage rename to dw1_pages_category__i;
alter index dw1_pages_site_publishedat rename to dw1_pages_publishedat__i;

