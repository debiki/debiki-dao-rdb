
create table settings_3 (
  site_id varchar not null,
  category_id int,
  page_id varchar,
  title varchar,
  description varchar,
  user_must_be_auth bool,
  user_must_be_approved bool,
  allow_guest_login bool,
  num_first_posts_to_review smallint,
  num_first_posts_to_approve smallint,
  num_first_posts_to_allow smallint,
  head_styles_html varchar,
  head_scripts_html varchar,
  end_of_body_html varchar,
  header_html varchar,
  footer_html varchar,
  show_forum_categories bool,
  horizontal_comments bool,
  social_links_html varchar,
  logo_url_or_html varchar,
  company_domain varchar,
  company_full_name varchar,
  company_short_name varchar,
  google_analytics_id varchar,
  experimental bool,

  constraint settings3_site__r__sites foreign key (site_id) references dw1_tenants(id),
  constraint settings3_cat__r__cats foreign key (site_id, category_id)
      references dw2_categories(site_id, id),
  constraint settings3_page__r__pages foreign key (site_id, page_id)
      references dw1_pages(site_id, page_id),
  constraint settings3_page_or_cat_null__c check (category_id is null or page_id is null),
  constraint settings3_title__c_len check (length(title) between 1 and 100),
  constraint settings3_descr__c_len check (length(description) between 1 and 1000),
  constraint settings3_auth_guest__c check (
      not (allow_guest_login and (user_must_be_auth or user_must_be_approved))),
  constraint settings3_numfirst_allow_ge_approve check (
      num_first_posts_to_allow >= num_first_posts_to_approve),
  constraint settings3_numfirsttoreview_0_to_10 check (num_first_posts_to_review between 0 and 10),
  constraint settings3_numfirsttoapprove_0_to_10 check (num_first_posts_to_approve between 0 and 10),
  constraint settings3_numfirsttoallow_0_to_10 check (num_first_posts_to_allow between 0 and 10),
  constraint settings3_headstyleshtml__c_len check (length(head_styles_html) between 1 and 20000),
  constraint settings3_headscriptshtml__c_len check (length(head_scripts_html) between 1 and 20000),
  constraint settings3_endofbodyhtml__c_len check (length(end_of_body_html) between 1 and 20000),
  constraint settings3_headerhtml__c_len check (length(header_html) between 1 and 20000),
  constraint settings3_footerhtml__c_len check (length(footer_html) between 1 and 20000),
  constraint settings3_sociallinkshtml__c_len check (length(social_links_html) between 1 and 20000),
  constraint settings3_logourlorhtml__c_len check (length(logo_url_or_html) between 1 and 20000),
  constraint settings3_companydomain__c_len check (length(company_domain) between 1 and 100),
  constraint settings3_companyfullname__c_len check (length(company_full_name) between 1 and 100),
  constraint settings3_companyshortname__c_len check (length(company_short_name) between 1 and 100),
  constraint settings3_googleanalyticsid__c_len check (length(google_analytics_id) between 1 and 100)
);


create index settings3_site__i on settings_3 (site_id);

create unique index settings3_site_category on settings_3 (site_id, category_id)
  where category_id is not null;

create unique index settings3_site_page on settings_3 (site_id, page_id)
  where category_id is not null;


