delete from email_subscriptions es where es.subscription_type = 'DAILY' and es.event_type_id not in (select id from event_type where application_id in (select aet.application_id from aggregation_email_template aet));

